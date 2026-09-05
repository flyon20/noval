from __future__ import annotations

import copy
import hashlib
import json
import math
import re
from dataclasses import dataclass, replace
from typing import Any, Callable, Iterable, Mapping

from app.models.knowledge import KnowledgeChatRequest
from app.services.harness.context_policy import current_context_policy


COMPACTION_VERSION = "noval-context-compaction-v1"
_SURFACE_FINGERPRINT_PREFIX = "sha256:"
_MAX_SURFACE_COUNT = 4_096
_STATE_PATTERN = re.compile(r"^<!-- NOVAL_CONTEXT_STATE_V1 (\{.*?\}) -->\s*", re.DOTALL)
_CONSTRAINT_PATTERN = re.compile(
    r"必须|不要|不能|不得|需要|务必|保持|约束|偏好|目标|限制|must|do not|don't|never|required|constraint",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class ModelContextCapability:
    context_window_tokens: int
    max_output_tokens: int = 16_384
    compaction_threshold_ratio: float = 0.85
    reserved_output_tokens: int = 8_192
    safety_margin_tokens: int = 8_192
    target_ratio: float = 0.62
    minimum_recent_turns: int = 4
    max_summary_tokens: int = 16_000

    def __post_init__(self) -> None:
        if self.context_window_tokens < 4_096:
            raise ValueError("context_window_tokens must be at least 4096")
        if self.max_output_tokens < 1:
            raise ValueError("max_output_tokens must be positive")
        if not 0.5 <= self.compaction_threshold_ratio < 1.0:
            raise ValueError("compaction_threshold_ratio must be in [0.5, 1.0)")
        if not 0.2 <= self.target_ratio < self.compaction_threshold_ratio:
            raise ValueError("target_ratio must be below compaction_threshold_ratio")
        if self.reserved_output_tokens < 0 or self.safety_margin_tokens < 0:
            raise ValueError("token reserves must be non-negative")
        if self.minimum_recent_turns < 1 or self.max_summary_tokens < 256:
            raise ValueError("recent-turn and summary limits are invalid")

    @classmethod
    def from_mapping(
        cls,
        value: Mapping[str, Any],
        *,
        fallback: "ModelContextCapability | None" = None,
    ) -> "ModelContextCapability":
        base = fallback or cls(context_window_tokens=DEFAULT_CONTEXT_WINDOW_TOKENS)

        def pick(*names: str, default: Any) -> Any:
            for name in names:
                if name in value and value[name] is not None:
                    return value[name]
            return default

        return cls(
            context_window_tokens=int(pick(
                "contextWindowTokens",
                "context_window_tokens",
                default=base.context_window_tokens,
            )),
            max_output_tokens=int(pick(
                "maxOutputTokens",
                "max_output_tokens",
                default=base.max_output_tokens,
            )),
            compaction_threshold_ratio=float(pick(
                "compactionThresholdRatio",
                "compaction_threshold_ratio",
                default=base.compaction_threshold_ratio,
            )),
            reserved_output_tokens=int(pick(
                "reservedOutputTokens",
                "reserved_output_tokens",
                default=base.reserved_output_tokens,
            )),
            safety_margin_tokens=int(pick(
                "safetyMarginTokens",
                "safety_margin_tokens",
                default=base.safety_margin_tokens,
            )),
            target_ratio=float(pick(
                "targetRatio",
                "target_ratio",
                default=base.target_ratio,
            )),
            minimum_recent_turns=int(pick(
                "minimumRecentTurns",
                "minimum_recent_turns",
                default=base.minimum_recent_turns,
            )),
            max_summary_tokens=int(pick(
                "maxSummaryTokens",
                "max_summary_tokens",
                default=base.max_summary_tokens,
            )),
        )


DEFAULT_CONTEXT_WINDOW_TOKENS = 300_000

# 统一 300k：之前兜底 128k、只有 deepseek 四个型号是 1M，导致换模型就换窗口——
# 线上 gpt-5.6-sol 不在表里拿到 128k，run 预算塌到 64k，证据稍多必然降级。
# 窗口现在由治理项（request.limits.maxInputTokens）权威覆盖，见 context_policy。
_DEFAULT_CAPABILITIES: dict[str, ModelContextCapability] = {
    "*": ModelContextCapability(context_window_tokens=DEFAULT_CONTEXT_WINDOW_TOKENS),
    "deepseek-v4-flash": ModelContextCapability(
        context_window_tokens=DEFAULT_CONTEXT_WINDOW_TOKENS,
        max_output_tokens=384_000,
        reserved_output_tokens=32_768,
        safety_margin_tokens=16_384,
        minimum_recent_turns=6,
        max_summary_tokens=32_000,
    ),
    "deepseek-v4-pro": ModelContextCapability(
        context_window_tokens=DEFAULT_CONTEXT_WINDOW_TOKENS,
        max_output_tokens=384_000,
        reserved_output_tokens=32_768,
        safety_margin_tokens=16_384,
        minimum_recent_turns=6,
        max_summary_tokens=32_000,
    ),
    "deepseek-chat": ModelContextCapability(
        context_window_tokens=DEFAULT_CONTEXT_WINDOW_TOKENS,
        max_output_tokens=384_000,
        reserved_output_tokens=32_768,
        safety_margin_tokens=16_384,
        minimum_recent_turns=6,
        max_summary_tokens=32_000,
    ),
    "deepseek-reasoner": ModelContextCapability(
        context_window_tokens=DEFAULT_CONTEXT_WINDOW_TOKENS,
        max_output_tokens=384_000,
        reserved_output_tokens=32_768,
        safety_margin_tokens=16_384,
        minimum_recent_turns=6,
        max_summary_tokens=32_000,
    ),
}


class ModelContextCapabilityRegistry:
    def __init__(self, capabilities: Mapping[str, ModelContextCapability] | None = None) -> None:
        merged = dict(_DEFAULT_CAPABILITIES)
        if capabilities:
            merged.update({str(key).strip().lower(): value for key, value in capabilities.items()})
        self._capabilities = merged

    @classmethod
    def from_json(cls, raw: str | None) -> "ModelContextCapabilityRegistry":
        if raw is None or not str(raw).strip():
            return cls()
        try:
            payload = json.loads(str(raw))
        except json.JSONDecodeError as exc:
            raise ValueError("AI_MODEL_CONTEXT_CAPABILITIES_JSON must be valid JSON") from exc
        if not isinstance(payload, dict):
            raise ValueError("AI_MODEL_CONTEXT_CAPABILITIES_JSON must be a JSON object")
        fallback = _DEFAULT_CAPABILITIES["*"]
        capabilities: dict[str, ModelContextCapability] = {}
        wildcard = payload.get("*")
        if isinstance(wildcard, dict):
            fallback = ModelContextCapability.from_mapping(wildcard, fallback=fallback)
            capabilities["*"] = fallback
        for model, value in payload.items():
            if model == "*":
                continue
            if not isinstance(value, dict):
                raise ValueError(f"model capability for {model!r} must be an object")
            capabilities[str(model).strip().lower()] = ModelContextCapability.from_mapping(
                value,
                fallback=fallback,
            )
        return cls(capabilities)

    def resolve(self, model: str | None) -> ModelContextCapability:
        normalized = str(model or "").strip().lower()
        return self._capabilities.get(normalized, self._capabilities["*"])


@dataclass(frozen=True)
class ContextCompactionResult:
    request: KnowledgeChatRequest
    status: str
    reason: str
    model: str
    capability: ModelContextCapability
    before_input_tokens: int
    after_input_tokens: int
    threshold_tokens: int
    target_tokens: int
    retained_message_count: int = 0
    retained_turn_count: int = 0
    summarized_message_count: int = 0
    reused_message_count: int = 0
    generation: int = 0
    coverage_fingerprint: str | None = None
    compacted_summary: str | None = None

    @property
    def compacted(self) -> bool:
        return self.status == "compacted"

    def trace_summary(self, *, include_summary: bool = False) -> dict[str, Any]:
        summary: dict[str, Any] = {
            "version": COMPACTION_VERSION,
            "status": self.status,
            "reason": self.reason,
            "model": self.model,
            "contextWindowTokens": self.capability.context_window_tokens,
            "maxOutputTokens": self.capability.max_output_tokens,
            "thresholdTokens": self.threshold_tokens,
            "targetTokens": self.target_tokens,
            "beforeInputTokens": self.before_input_tokens,
            "afterInputTokens": self.after_input_tokens,
            "retainedMessageCount": self.retained_message_count,
            "retainedTurnCount": self.retained_turn_count,
            "summarizedMessageCount": self.summarized_message_count,
            "reusedMessageCount": self.reused_message_count,
            "generation": self.generation,
        }
        if self.coverage_fingerprint:
            summary["coverageFingerprint"] = self.coverage_fingerprint
        if include_summary and self.compacted_summary:
            summary["compactedSummary"] = self.compacted_summary
        return summary

    def events(self) -> list[dict[str, Any]]:
        if not self.compacted:
            return []
        common = {
            "phase": "context",
            "version": COMPACTION_VERSION,
            "model": self.model,
            "contextWindowTokens": self.capability.context_window_tokens,
            "thresholdTokens": self.threshold_tokens,
        }
        return [
            {
                "event": "context_compacting",
                **common,
                "message": "会话接近模型上下文上限，正在自动压缩",
                "beforeInputTokens": self.before_input_tokens,
            },
            {
                "event": "context_compacted",
                **common,
                "message": "上下文已自动压缩",
                "beforeInputTokens": self.before_input_tokens,
                "afterInputTokens": self.after_input_tokens,
                "retainedTurnCount": self.retained_turn_count,
                "summarizedMessageCount": self.summarized_message_count,
                "generation": self.generation,
            },
        ]


@dataclass(frozen=True)
class ProviderEnvelopeCompactionResult:
    messages: list[dict[str, Any]]
    status: str
    reason: str
    model: str
    capability: ModelContextCapability
    before_input_tokens: int
    after_input_tokens: int
    threshold_tokens: int
    target_tokens: int
    retained_message_count: int = 0
    retained_turn_count: int = 0
    summarized_message_count: int = 0
    pruned_tool_result_count: int = 0
    before_surface_fingerprint: str = ""
    after_surface_fingerprint: str = ""
    before_message_count: int = 0
    after_message_count: int = 0
    before_tool_call_count: int = 0
    after_tool_call_count: int = 0
    before_tool_result_count: int = 0
    after_tool_result_count: int = 0
    tool_schema_count: int = 0

    @property
    def compacted(self) -> bool:
        return self.status == "compacted"

    def trace_summary(self) -> dict[str, Any]:
        return {
            "version": COMPACTION_VERSION,
            "surface": "provider_envelope",
            "status": self.status,
            "reason": self.reason,
            "model": self.model,
            "contextWindowTokens": self.capability.context_window_tokens,
            "thresholdTokens": self.threshold_tokens,
            "targetTokens": self.target_tokens,
            "beforeInputTokens": self.before_input_tokens,
            "afterInputTokens": self.after_input_tokens,
            "retainedMessageCount": self.retained_message_count,
            "retainedTurnCount": self.retained_turn_count,
            "summarizedMessageCount": self.summarized_message_count,
            "prunedToolResultCount": self.pruned_tool_result_count,
            "beforeSurfaceFingerprint": self.before_surface_fingerprint,
            "afterSurfaceFingerprint": self.after_surface_fingerprint,
            "beforeMessageCount": self.before_message_count,
            "afterMessageCount": self.after_message_count,
            "beforeToolCallCount": self.before_tool_call_count,
            "afterToolCallCount": self.after_tool_call_count,
            "beforeToolResultCount": self.before_tool_result_count,
            "afterToolResultCount": self.after_tool_result_count,
            "toolSchemaCount": self.tool_schema_count,
            "bodyRedacted": True,
        }


class ProviderEnvelopeCompactionError(RuntimeError):
    def __init__(self, result: ProviderEnvelopeCompactionResult) -> None:
        self.result = result
        super().__init__(
            "final provider envelope could not be compacted below the target token budget"
        )


def estimate_text_tokens(value: str | None) -> int:
    text = str(value or "")
    if not text:
        return 0
    # UTF-8 bytes make CJK, kana, emoji and other non-ASCII text cost more than
    # Latin text. Three bytes/token is intentionally conservative for admission.
    byte_tokens = math.ceil(len(text.encode("utf-8")) / 3)
    structural_tokens = math.ceil((text.count("\n") + 1) / 8)
    return max(1, byte_tokens + structural_tokens)


def canonical_provider_request_envelope(
    messages: Iterable[Mapping[str, Any]],
    *,
    model: str | None = None,
    tool_schemas: Iterable[Mapping[str, Any]] = (),
    reasoning_mode: str | None = None,
    reasoning_effort: str | None = None,
    mode: str | None = None,
) -> dict[str, Any]:
    canonical_messages = [
        copy.deepcopy(dict(message))
        for message in messages
        if isinstance(message, Mapping)
    ]
    canonical_tools = [
        copy.deepcopy(dict(schema))
        for schema in tool_schemas
        if isinstance(schema, Mapping)
    ]
    canonical_tools.sort(key=lambda schema: json.dumps(
        schema,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ))
    return {
        "model": str(model or "").strip() or None,
        "messages": canonical_messages,
        "tools": canonical_tools,
        "reasoningMode": str(reasoning_mode or "").strip() or None,
        "reasoningEffort": str(reasoning_effort or "").strip() or None,
        "mode": str(mode or "").strip() or None,
    }


def _bounded_surface_count(value: int) -> int:
    return min(max(0, int(value)), _MAX_SURFACE_COUNT)


def _provider_surface_metadata(
    messages: Iterable[Mapping[str, Any]],
    *,
    model: str | None,
    tool_schemas: Iterable[Mapping[str, Any]],
    reasoning_mode: str | None = None,
    reasoning_effort: str | None = None,
) -> dict[str, Any]:
    normalized_messages = [
        dict(message)
        for message in messages
        if isinstance(message, Mapping)
    ]
    normalized_tools = [
        dict(schema)
        for schema in tool_schemas
        if isinstance(schema, Mapping)
    ]
    envelope = canonical_provider_request_envelope(
        normalized_messages,
        model=model,
        tool_schemas=normalized_tools,
        reasoning_mode=reasoning_mode,
        reasoning_effort=reasoning_effort,
    )
    serialized = json.dumps(
        envelope,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    tool_call_count = sum(
        len(message.get("tool_calls"))
        for message in normalized_messages
        if isinstance(message.get("tool_calls"), list)
    )
    tool_result_count = sum(
        1
        for message in normalized_messages
        if str(message.get("role") or "").strip().lower() == "tool"
    )
    return {
        "fingerprint": _SURFACE_FINGERPRINT_PREFIX + hashlib.sha256(
            serialized.encode("utf-8")
        ).hexdigest(),
        "message_count": _bounded_surface_count(len(normalized_messages)),
        "tool_call_count": _bounded_surface_count(tool_call_count),
        "tool_result_count": _bounded_surface_count(tool_result_count),
        "tool_schema_count": _bounded_surface_count(len(normalized_tools)),
    }


def _provider_surface_result_fields(
    metadata: Mapping[str, Any],
    *,
    prefix: str,
) -> dict[str, Any]:
    return {
        f"{prefix}_surface_fingerprint": str(metadata["fingerprint"]),
        f"{prefix}_message_count": int(metadata["message_count"]),
        f"{prefix}_tool_call_count": int(metadata["tool_call_count"]),
        f"{prefix}_tool_result_count": int(metadata["tool_result_count"]),
    }


def canonical_provider_envelope(
    request: KnowledgeChatRequest,
    *,
    model: str | None = None,
    tool_schemas: Iterable[Mapping[str, Any]] = (),
) -> dict[str, Any]:
    messages: list[dict[str, Any]] = []
    if request.contextSummary:
        messages.append({"role": "system", "content": request.contextSummary})
    messages.extend(dict(message) for message in request.history if isinstance(message, Mapping))
    messages.append({"role": "user", "content": request.question})
    if isinstance(request.contextBundle, dict) and request.contextBundle:
        messages.append({
            "role": "system",
            "content": json.dumps(
                request.contextBundle,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            ),
        })
    return canonical_provider_request_envelope(
        messages,
        model=model,
        tool_schemas=tool_schemas,
        reasoning_mode=request.reasoningMode,
        mode=request.mode,
    )


def estimate_provider_input_tokens(
    messages: Iterable[Mapping[str, Any]],
    *,
    model: str | None = None,
    tool_schemas: Iterable[Mapping[str, Any]] = (),
    reasoning_mode: str | None = None,
    reasoning_effort: str | None = None,
    mode: str | None = None,
) -> int:
    serialized = json.dumps(
        canonical_provider_request_envelope(
            messages,
            model=model,
            tool_schemas=tool_schemas,
            reasoning_mode=reasoning_mode,
            reasoning_effort=reasoning_effort,
            mode=mode,
        ),
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    # Include a fixed allowance for provider-side role/tool framing.
    return estimate_text_tokens(serialized) + 1_024


def estimate_request_input_tokens(
    request: KnowledgeChatRequest,
    *,
    model: str | None = None,
    tool_schemas: Iterable[Mapping[str, Any]] = (),
) -> int:
    envelope = canonical_provider_envelope(
        request,
        model=model,
        tool_schemas=tool_schemas,
    )
    return estimate_provider_input_tokens(
        envelope["messages"],
        model=model,
        tool_schemas=envelope["tools"],
        reasoning_mode=request.reasoningMode,
        mode=request.mode,
    )


class ContextCompactor:
    def __init__(
        self,
        capability: ModelContextCapability | None = None,
        *,
        registry: ModelContextCapabilityRegistry | None = None,
        enabled: bool = True,
    ) -> None:
        self._fixed_capability = capability
        self._registry = registry or ModelContextCapabilityRegistry()
        self.enabled = enabled

    def capability_for(self, model: str | None) -> ModelContextCapability:
        base = self._fixed_capability or self._registry.resolve(model)
        return self._with_run_policy(base)

    @staticmethod
    def _with_run_policy(capability: ModelContextCapability) -> ModelContextCapability:
        """把本次 run 的治理值折进能力表行。

        请求层（``prepare``）和 provider 信封层（``prepare_provider_envelope``）都只经过
        ``capability_for`` 拿能力，所以在这里改一次，两层看到的窗口和阈值就必然一致——
        信封层拿不到 request，靠自己是读不到治理值的。
        """
        policy = current_context_policy()
        if policy.empty:
            return capability
        return replace(
            capability,
            context_window_tokens=policy.window_for(capability.context_window_tokens),
            compaction_threshold_ratio=policy.threshold_ratio_for(
                capability.compaction_threshold_ratio
            ),
            target_ratio=policy.target_ratio_for(
                capability.compaction_threshold_ratio,
                capability.target_ratio,
            ),
        )

    def context_window_for(self, request: KnowledgeChatRequest, *, model: str | None) -> int:
        return self._context_window(request, self.capability_for(model))

    def prepare_provider_envelope(
        self,
        messages: Iterable[Mapping[str, Any]],
        *,
        model: str | None,
        tool_schemas: Iterable[Mapping[str, Any]] = (),
        max_output_tokens: int | None = None,
        reasoning_mode: str | None = None,
        reasoning_effort: str | None = None,
    ) -> ProviderEnvelopeCompactionResult:
        model_name = str(model or "").strip() or "unknown"
        capability = self.capability_for(model_name)
        original = [
            copy.deepcopy(dict(message))
            for message in messages
            if isinstance(message, Mapping)
        ]
        tools = [
            copy.deepcopy(dict(schema))
            for schema in tool_schemas
            if isinstance(schema, Mapping)
        ]
        requested_output = self._safe_int(max_output_tokens)
        reserved_output = (
            min(requested_output, capability.max_output_tokens)
            if requested_output > 0
            else min(capability.reserved_output_tokens, capability.max_output_tokens)
        )
        threshold_tokens = max(
            4_096,
            int(capability.context_window_tokens * capability.compaction_threshold_ratio)
            - reserved_output
            - capability.safety_margin_tokens,
        )
        target_tokens = max(
            4_096,
            int(capability.context_window_tokens * capability.target_ratio)
            - reserved_output
            - capability.safety_margin_tokens,
        )

        def meter(candidate: Iterable[Mapping[str, Any]]) -> int:
            return estimate_provider_input_tokens(
                candidate,
                model=model_name,
                tool_schemas=tools,
                reasoning_mode=reasoning_mode,
                reasoning_effort=reasoning_effort,
            )

        before_tokens = meter(original)
        _, original_turns = self._provider_prefix_and_turns(original)
        before_surface = _provider_surface_metadata(
            original,
            model=model_name,
            tool_schemas=tools,
            reasoning_mode=reasoning_mode,
            reasoning_effort=reasoning_effort,
        )
        common = {
            "model": model_name,
            "capability": capability,
            "before_input_tokens": before_tokens,
            "threshold_tokens": threshold_tokens,
            "target_tokens": target_tokens,
        }
        common.update(_provider_surface_result_fields(before_surface, prefix="before"))
        common["tool_schema_count"] = int(before_surface["tool_schema_count"])
        if not self.enabled:
            return ProviderEnvelopeCompactionResult(
                messages=original,
                status="disabled",
                reason="compaction_disabled",
                after_input_tokens=before_tokens,
                retained_message_count=len(original),
                retained_turn_count=len(original_turns),
                **_provider_surface_result_fields(before_surface, prefix="after"),
                **common,
            )
        if before_tokens < threshold_tokens:
            return ProviderEnvelopeCompactionResult(
                messages=original,
                status="not_needed",
                reason="below_threshold",
                after_input_tokens=before_tokens,
                retained_message_count=len(original),
                retained_turn_count=len(original_turns),
                **_provider_surface_result_fields(before_surface, prefix="after"),
                **common,
            )

        candidate, pruned_count = self._prune_provider_tool_results(
            original,
            target_tokens,
        )
        candidate_tokens = meter(candidate)
        summarized_count = 0
        if candidate_tokens > target_tokens:
            candidate, summarized_count = self._compact_provider_turns(
                candidate,
                capability=capability,
                target_tokens=target_tokens,
                meter=meter,
            )
            candidate_tokens = meter(candidate)

        _, retained_turns = self._provider_prefix_and_turns(candidate)
        if candidate_tokens >= before_tokens or candidate_tokens > target_tokens:
            return ProviderEnvelopeCompactionResult(
                messages=original,
                status="failed",
                reason="compaction_not_converged",
                # No candidate is dispatched on failure; report the original
                # surface as the effective after-state and keep attempted
                # candidate sizing out of the public summary.
                after_input_tokens=before_tokens,
                retained_message_count=len(original),
                retained_turn_count=len(original_turns),
                summarized_message_count=summarized_count,
                pruned_tool_result_count=pruned_count,
                **_provider_surface_result_fields(before_surface, prefix="after"),
                **common,
            )

        if summarized_count and pruned_count:
            reason = "tool_results_pruned_and_old_turns_summarized"
        elif summarized_count:
            reason = "old_turns_summarized"
        else:
            reason = "oversized_tool_results_pruned"
        return ProviderEnvelopeCompactionResult(
            messages=candidate,
            status="compacted",
            reason=reason,
            after_input_tokens=candidate_tokens,
            retained_message_count=len(candidate),
            retained_turn_count=len(retained_turns),
            summarized_message_count=summarized_count,
            pruned_tool_result_count=pruned_count,
            **_provider_surface_result_fields(
                _provider_surface_metadata(
                    candidate,
                    model=model_name,
                    tool_schemas=tools,
                    reasoning_mode=reasoning_mode,
                    reasoning_effort=reasoning_effort,
                ),
                prefix="after",
            ),
            **common,
        )

    def prepare(self, request: KnowledgeChatRequest, *, model: str | None) -> ContextCompactionResult:
        model_name = str(model or "").strip() or "unknown"
        capability = self.capability_for(model_name)
        context_window = self._context_window(request, capability)
        reserved_output = self._reserved_output(request, capability)
        threshold_tokens = max(
            4_096,
            int(context_window * capability.compaction_threshold_ratio)
            - reserved_output
            - capability.safety_margin_tokens,
        )
        target_tokens = max(
            4_096,
            int(context_window * capability.target_ratio)
            - reserved_output
            - capability.safety_margin_tokens,
        )
        before_tokens = estimate_request_input_tokens(request, model=model_name)
        effective_request, reused_count = self._apply_persisted_coverage(request)
        effective_tokens = estimate_request_input_tokens(effective_request, model=model_name)
        base = {
            "model": model_name,
            "capability": capability,
            "before_input_tokens": before_tokens,
            "after_input_tokens": effective_tokens,
            "threshold_tokens": threshold_tokens,
            "target_tokens": target_tokens,
            "retained_message_count": len(effective_request.history),
            "retained_turn_count": len(self._atomic_turns(effective_request.history)),
            "reused_message_count": reused_count,
        }
        if not self.enabled:
            return ContextCompactionResult(
                request=request,
                status="disabled",
                reason="compaction_disabled",
                **base,
            )
        if effective_tokens < threshold_tokens:
            return ContextCompactionResult(
                request=effective_request if reused_count else request,
                status="reused" if reused_count else "not_needed",
                reason="persisted_compaction_reused" if reused_count else "below_threshold",
                **base,
            )
        try:
            return self._compact(
                effective_request,
                model_name=model_name,
                capability=capability,
                before_tokens=before_tokens,
                threshold_tokens=threshold_tokens,
                target_tokens=target_tokens,
                reused_count=reused_count,
            )
        except Exception:
            return ContextCompactionResult(
                request=request,
                status="failed",
                reason="compaction_failed",
                **base,
            )

    def _compact(
        self,
        request: KnowledgeChatRequest,
        *,
        model_name: str,
        capability: ModelContextCapability,
        before_tokens: int,
        threshold_tokens: int,
        target_tokens: int,
        reused_count: int,
    ) -> ContextCompactionResult:
        turns = self._atomic_turns(request.history)
        keep_start = max(0, len(turns) - capability.minimum_recent_turns)
        candidate, summary, covered, generation = self._candidate_request(
            request,
            turns,
            keep_start,
            capability,
        )
        candidate_tokens = estimate_request_input_tokens(candidate, model=model_name)

        while keep_start > 0:
            trial, trial_summary, trial_covered, trial_generation = self._candidate_request(
                request,
                turns,
                keep_start - 1,
                capability,
            )
            trial_tokens = estimate_request_input_tokens(trial, model=model_name)
            if trial_tokens > target_tokens:
                break
            keep_start -= 1
            candidate = trial
            summary = trial_summary
            covered = trial_covered
            generation = trial_generation
            candidate_tokens = trial_tokens

        if candidate_tokens > target_tokens and candidate.history:
            candidate = self._prune_oversized_tool_results(candidate, target_tokens)
            candidate_tokens = estimate_request_input_tokens(candidate, model=model_name)
        if candidate_tokens > target_tokens and candidate.history:
            candidate, candidate_tokens = self._fit_request_history_to_target(
                candidate,
                summary=summary,
                model_name=model_name,
                target_tokens=target_tokens,
            )

        if candidate_tokens >= before_tokens or candidate_tokens > target_tokens:
            return ContextCompactionResult(
                request=request,
                status="failed",
                reason="compaction_not_converged",
                model=model_name,
                capability=capability,
                before_input_tokens=before_tokens,
                after_input_tokens=candidate_tokens,
                threshold_tokens=threshold_tokens,
                target_tokens=target_tokens,
                retained_message_count=len(request.history),
                retained_turn_count=len(turns),
                reused_message_count=reused_count,
            )

        dropped_messages = [message for turn in turns[:keep_start] for message in turn]
        retained_turns = self._atomic_turns(candidate.history)
        coverage_fingerprint = self._coverage_fingerprint(covered)
        return ContextCompactionResult(
            request=candidate,
            status="compacted",
            reason="projected_input_near_model_limit",
            model=model_name,
            capability=capability,
            before_input_tokens=before_tokens,
            after_input_tokens=candidate_tokens,
            threshold_tokens=threshold_tokens,
            target_tokens=target_tokens,
            retained_message_count=len(candidate.history),
            retained_turn_count=len(retained_turns),
            summarized_message_count=len(dropped_messages),
            reused_message_count=reused_count,
            generation=generation,
            coverage_fingerprint=coverage_fingerprint,
            compacted_summary=summary,
        )

    def _candidate_request(
        self,
        request: KnowledgeChatRequest,
        turns: list[list[dict[str, str]]],
        keep_start: int,
        capability: ModelContextCapability,
    ) -> tuple[KnowledgeChatRequest, str, set[str], int]:
        dropped = [message for turn in turns[:keep_start] for message in turn]
        retained = [message for turn in turns[keep_start:] for message in turn]
        state, existing_body = self._summary_state(request.contextSummary)
        covered = set(state.get("covered") or [])
        covered.update(self._message_fingerprint(message) for message in dropped)
        generation = max(0, self._safe_int(state.get("generation"))) + 1
        summary = self._build_summary(
            existing_body,
            dropped,
            covered=covered,
            generation=generation,
            max_tokens=capability.max_summary_tokens,
        )
        return self._request_with_context(request, retained, summary), summary, covered, generation

    def _build_summary(
        self,
        existing_summary: str,
        dropped_messages: list[dict[str, str]],
        *,
        covered: set[str],
        generation: int,
        max_tokens: int,
    ) -> str:
        sections: list[str] = []
        if existing_summary.strip():
            sections.append("既有会话摘要：\n" + existing_summary.strip())
        constraints = self._visible_constraints(dropped_messages)
        if constraints:
            sections.append("长期目标与硬约束：\n" + "\n".join(f"- {item}" for item in constraints))
        turn_summaries: list[str] = []
        for turn in self._atomic_turns(dropped_messages):
            user_parts = [item["content"] for item in turn if item.get("role") == "user"]
            assistant_parts = [item["content"] for item in turn if item.get("role") == "assistant"]
            tool_parts = [item["content"] for item in turn if item.get("role") == "tool"]
            pieces: list[str] = []
            if user_parts:
                pieces.append("用户目标=" + self._truncate_to_tokens(" ".join(user_parts), 140))
            if assistant_parts:
                pieces.append("可见结论=" + self._truncate_to_tokens(" ".join(assistant_parts), 180))
            if tool_parts:
                pieces.append(f"工具结果={len(tool_parts)} 项（正文不进入摘要）")
            if pieces:
                turn_summaries.append("- " + "；".join(pieces))
        if turn_summaries:
            sections.append("较早对话的结构化摘要：\n" + "\n".join(turn_summaries))
        body = "\n\n".join(sections).strip() or "较早对话已压缩，未保留隐藏推理或工具参数。"
        covered_values = sorted(covered)[-512:]
        state = json.dumps(
            {"covered": covered_values, "generation": generation, "version": COMPACTION_VERSION},
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        marker = f"<!-- NOVAL_CONTEXT_STATE_V1 {state} -->\n"
        body_budget = max(256, max_tokens - estimate_text_tokens(marker))
        return marker + self._truncate_to_tokens(body, body_budget)

    def _apply_persisted_coverage(
        self,
        request: KnowledgeChatRequest,
    ) -> tuple[KnowledgeChatRequest, int]:
        state, _body = self._summary_state(request.contextSummary)
        covered = {str(value) for value in list(state.get("covered") or []) if str(value)}
        if not covered:
            return request, 0
        retained = [
            dict(message)
            for message in request.history
            if self._message_fingerprint(message) not in covered
        ]
        removed = len(request.history) - len(retained)
        if removed <= 0:
            return request, 0
        return self._request_with_context(request, retained, request.contextSummary), removed

    def _request_with_context(
        self,
        request: KnowledgeChatRequest,
        history: list[dict[str, str]],
        summary: str | None,
    ) -> KnowledgeChatRequest:
        bundle = copy.deepcopy(request.contextBundle) if isinstance(request.contextBundle, dict) else request.contextBundle
        if isinstance(bundle, dict):
            layer = bundle.get("threadSummary")
            if not isinstance(layer, dict):
                layer = {"scope": "thread", "content": {}}
                bundle["threadSummary"] = layer
            content = layer.get("content")
            if not isinstance(content, dict):
                content = {}
                layer["content"] = content
            content["history"] = [dict(message) for message in history]
            if summary:
                content["summary"] = summary
            else:
                content.pop("summary", None)
        return request.model_copy(
            update={
                "history": [dict(message) for message in history],
                "contextSummary": summary,
                "contextBundle": bundle,
            },
            deep=True,
        )

    def _fit_history(
        self,
        history: list[dict[str, str]],
        target_tokens: int,
    ) -> list[dict[str, str]]:
        per_message = max(128, target_tokens // max(len(history), 1))
        fitted: list[dict[str, str]] = []
        for message in history:
            item = dict(message)
            item["content"] = self._truncate_to_tokens(str(item.get("content") or ""), per_message)
            fitted.append(item)
        return fitted

    def _fit_request_history_to_target(
        self,
        request: KnowledgeChatRequest,
        *,
        summary: str,
        model_name: str,
        target_tokens: int,
    ) -> tuple[KnowledgeChatRequest, int]:
        best = request
        best_tokens = estimate_request_input_tokens(best, model=model_name)
        floor_budget = max(128, 128 * len(request.history))
        fit_budget = max(floor_budget, target_tokens)
        while True:
            fitted_history = self._fit_history(request.history, fit_budget)
            trial = self._request_with_context(request, fitted_history, summary)
            trial_tokens = estimate_request_input_tokens(trial, model=model_name)
            if trial_tokens < best_tokens:
                best = trial
                best_tokens = trial_tokens
            if trial_tokens <= target_tokens or fit_budget <= floor_budget:
                break
            next_budget = max(floor_budget, fit_budget // 2)
            if next_budget == fit_budget:
                break
            fit_budget = next_budget
        return best, best_tokens

    def _prune_oversized_tool_results(
        self,
        request: KnowledgeChatRequest,
        target_tokens: int,
    ) -> KnowledgeChatRequest:
        tool_count = sum(1 for message in request.history if str(message.get("role") or "").lower() == "tool")
        if tool_count == 0:
            return request
        per_tool_budget = max(256, target_tokens // max(2, tool_count * 2))
        history: list[dict[str, str]] = []
        for raw in request.history:
            item = dict(raw)
            if str(item.get("role") or "").lower() == "tool":
                item["content"] = self._truncate_to_tokens(str(item.get("content") or ""), per_tool_budget)
            history.append(item)
        return self._request_with_context(request, history, request.contextSummary)

    def _prune_provider_tool_results(
        self,
        messages: list[dict[str, Any]],
        target_tokens: int,
    ) -> tuple[list[dict[str, Any]], int]:
        tool_count = sum(
            1
            for message in messages
            if str(message.get("role") or "").strip().lower() == "tool"
        )
        if tool_count == 0:
            return [copy.deepcopy(message) for message in messages], 0
        per_tool_budget = max(256, target_tokens // max(2, tool_count * 2))
        candidate: list[dict[str, Any]] = []
        pruned_count = 0
        for raw in messages:
            item = copy.deepcopy(raw)
            if str(item.get("role") or "").strip().lower() == "tool":
                content = str(item.get("content") or "")
                compacted = self._truncate_to_tokens(content, per_tool_budget)
                if compacted != content:
                    item["content"] = compacted
                    pruned_count += 1
            candidate.append(item)
        return candidate, pruned_count

    def _compact_provider_turns(
        self,
        messages: list[dict[str, Any]],
        *,
        capability: ModelContextCapability,
        target_tokens: int,
        meter: Callable[[Iterable[Mapping[str, Any]]], int],
    ) -> tuple[list[dict[str, Any]], int]:
        prefix, turns = self._provider_prefix_and_turns(messages)
        max_drop = max(0, len(turns) - capability.minimum_recent_turns)
        candidate = [copy.deepcopy(message) for message in messages]
        summarized_count = 0
        for drop_count in range(1, max_drop + 1):
            dropped = [message for turn in turns[:drop_count] for message in turn]
            retained = [message for turn in turns[drop_count:] for message in turn]
            summary = self._provider_turn_summary(
                dropped,
                max_tokens=min(capability.max_summary_tokens, max(256, target_tokens // 4)),
            )
            candidate = [copy.deepcopy(message) for message in prefix]
            if summary:
                candidate.append({"role": "system", "content": summary})
            candidate.extend(copy.deepcopy(message) for message in retained)
            summarized_count = len(dropped)
            if meter(candidate) <= target_tokens:
                break
        return candidate, summarized_count

    def _provider_turn_summary(
        self,
        messages: list[dict[str, Any]],
        *,
        max_tokens: int,
    ) -> str:
        lines: list[str] = []
        for turn in self._provider_prefix_and_turns(messages)[1]:
            user_parts = [
                str(message.get("content") or "")
                for message in turn
                if str(message.get("role") or "").lower() == "user"
            ]
            assistant_parts = [
                str(message.get("content") or "")
                for message in turn
                if str(message.get("role") or "").lower() == "assistant"
                and str(message.get("content") or "").strip()
            ]
            tool_count = sum(
                1
                for message in turn
                if str(message.get("role") or "").lower() == "tool"
            )
            pieces: list[str] = []
            if user_parts:
                pieces.append("user=" + self._truncate_to_tokens(" ".join(user_parts), 140))
            if assistant_parts:
                pieces.append(
                    "assistant=" + self._truncate_to_tokens(" ".join(assistant_parts), 180)
                )
            if tool_count:
                pieces.append(f"tool_results={tool_count} (bodies omitted)")
            if pieces:
                lines.append("- " + "; ".join(pieces))
        if not lines:
            return ""
        return self._truncate_to_tokens(
            "Earlier provider turns were compacted deterministically:\n" + "\n".join(lines),
            max_tokens,
        )

    @staticmethod
    def _provider_prefix_and_turns(
        messages: Iterable[Mapping[str, Any]],
    ) -> tuple[list[dict[str, Any]], list[list[dict[str, Any]]]]:
        normalized = [
            copy.deepcopy(dict(message))
            for message in messages
            if isinstance(message, Mapping)
        ]
        prefix: list[dict[str, Any]] = []
        body_start = 0
        for index, message in enumerate(normalized):
            role = str(message.get("role") or "").strip().lower()
            if role not in {"system", "developer"}:
                body_start = index
                break
            prefix.append(message)
        else:
            body_start = len(normalized)

        turns: list[list[dict[str, Any]]] = []
        current: list[dict[str, Any]] = []
        for message in normalized[body_start:]:
            role = str(message.get("role") or "user").strip().lower()
            if role == "user" and current:
                turns.append(current)
                current = []
            current.append(message)
        if current:
            turns.append(current)
        return prefix, turns

    @staticmethod
    def _atomic_turns(history: Iterable[Mapping[str, Any]]) -> list[list[dict[str, str]]]:
        turns: list[list[dict[str, str]]] = []
        current: list[dict[str, str]] = []
        for raw in history:
            content = str(raw.get("content") or "").strip()
            if not content:
                continue
            role = str(raw.get("role") or "user").strip().lower()
            if role not in {"user", "assistant", "tool"}:
                role = "user"
            if role == "user" and current:
                turns.append(current)
                current = []
            item = {str(key): str(value) for key, value in raw.items() if value is not None}
            item["role"] = role
            item["content"] = content
            current.append(item)
        if current:
            turns.append(current)
        return turns

    @staticmethod
    def _message_fingerprint(message: Mapping[str, Any]) -> str:
        canonical = json.dumps(
            {str(key): str(value) for key, value in message.items() if value is not None},
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    @staticmethod
    def _coverage_fingerprint(covered: set[str]) -> str | None:
        if not covered:
            return None
        canonical = "\n".join(sorted(covered))
        return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    def _visible_constraints(self, messages: list[dict[str, str]]) -> list[str]:
        constraints: list[str] = []
        seen: set[str] = set()
        for message in messages:
            if message.get("role") != "user":
                continue
            for sentence in re.split(r"[。！？!?\n]+", message.get("content") or ""):
                normalized = " ".join(sentence.split()).strip()
                if not normalized or not _CONSTRAINT_PATTERN.search(normalized):
                    continue
                normalized = self._truncate_to_tokens(normalized, 120)
                if normalized and normalized not in seen:
                    seen.add(normalized)
                    constraints.append(normalized)
                if len(constraints) >= 24:
                    return constraints
        return constraints

    @staticmethod
    def _summary_state(summary: str | None) -> tuple[dict[str, Any], str]:
        text = str(summary or "").strip()
        match = _STATE_PATTERN.match(text)
        if not match:
            return {}, text
        try:
            state = json.loads(match.group(1))
        except json.JSONDecodeError:
            return {}, text
        return (state if isinstance(state, dict) else {}), text[match.end():].strip()

    @staticmethod
    def _truncate_to_tokens(value: str, max_tokens: int) -> str:
        text = str(value or "").strip()
        if not text or estimate_text_tokens(text) <= max_tokens:
            return text
        low = 1
        high = len(text)
        while low < high:
            middle = (low + high + 1) // 2
            if estimate_text_tokens(text[:middle]) <= max_tokens:
                low = middle
            else:
                high = middle - 1
        if low >= len(text):
            return text
        marker = "…[已压缩]"
        prefix = text[:low].rstrip()
        while prefix and estimate_text_tokens(prefix + marker) > max_tokens:
            prefix = prefix[:-1]
        return prefix + marker

    @staticmethod
    def _safe_int(value: Any) -> int:
        try:
            return int(value or 0)
        except (TypeError, ValueError):
            return 0

    @staticmethod
    def _context_window(
        request: KnowledgeChatRequest,
        capability: ModelContextCapability,
    ) -> int:
        limits = request.limits if isinstance(request.limits, dict) else {}
        value = limits.get("maxInputTokens") or limits.get("max_input_tokens")
        try:
            requested = int(value) if value is not None else capability.context_window_tokens
        except (TypeError, ValueError):
            requested = capability.context_window_tokens
        return max(4_096, min(requested, capability.context_window_tokens))

    @staticmethod
    def _reserved_output(
        request: KnowledgeChatRequest,
        capability: ModelContextCapability,
    ) -> int:
        limits = request.limits if isinstance(request.limits, dict) else {}
        value = limits.get("maxOutputTokens") or limits.get("max_output_tokens")
        try:
            requested = int(value) if value is not None else capability.reserved_output_tokens
        except (TypeError, ValueError):
            requested = capability.reserved_output_tokens
        return max(0, min(requested, capability.max_output_tokens))
