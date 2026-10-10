from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Literal

from app.services.harness.context_policy import current_context_policy


RunMode = Literal["fast", "deep"]

FAST_TOTAL_TOKEN_LIMIT = 128_000
DEEP_TOTAL_TOKEN_LIMIT = 512_000
FAST_TOOL_CALL_LIMIT = 6
DEEP_TOOL_CALL_LIMIT = 12
FAST_DELEGATION_LIMIT = 1
DEEP_DELEGATION_LIMIT = 2
# 整个 run 的 token 总预算 = 上下文窗口 × 该比例。1.5 倍而非 0.5 倍：压缩把单次输入压到窗口的
# ~85%，而一次问答里 compose_answer 之外还有意图/路由/复核等几次小调用，0.5 倍会让"刚好压到
# 阈值以下"的请求第一刀就吃光预算，反过来逼出降级回答。治理项可覆盖，见 context_policy。
RUN_CONTEXT_WINDOW_SHARE = 1.5


class BudgetExceededError(RuntimeError):
    def __init__(self, resource: str, *, limit: int, requested: int, consumed: int) -> None:
        self.resource = resource
        self.limit = limit
        self.requested = requested
        self.consumed = consumed
        super().__init__(
            f"run {resource} budget exceeded: "
            f"limit={limit}, consumed={consumed}, requested={requested}"
        )


@dataclass(slots=True)
class RunBudget:
    mode: RunMode
    max_total_tokens: int
    max_tool_calls: int
    max_delegations: int
    used_total_tokens: int = 0
    used_tool_calls: int = 0
    used_delegations: int = 0

    def __post_init__(self) -> None:
        if self.mode not in {"fast", "deep"}:
            raise ValueError(f"unsupported run mode: {self.mode}")
        for name, value in (
            ("max_total_tokens", self.max_total_tokens),
            ("max_tool_calls", self.max_tool_calls),
            ("max_delegations", self.max_delegations),
        ):
            if value < 0:
                raise ValueError(f"{name} must be non-negative")

    @classmethod
    def for_mode(
        cls,
        mode: str,
        *,
        context_window_tokens: int | None = None,
    ) -> "RunBudget":
        normalized_mode = mode.strip().lower()
        dynamic_total_tokens: int | None = None
        if context_window_tokens is not None:
            try:
                parsed_context_window = max(1, int(context_window_tokens))
            except (TypeError, ValueError):
                parsed_context_window = 0
            if parsed_context_window > 0:
                dynamic_total_tokens = current_context_policy().run_budget_tokens(
                    parsed_context_window,
                    default_share=RUN_CONTEXT_WINDOW_SHARE,
                )
        if normalized_mode == "fast":
            return cls(
                mode="fast",
                max_total_tokens=dynamic_total_tokens or FAST_TOTAL_TOKEN_LIMIT,
                max_tool_calls=FAST_TOOL_CALL_LIMIT,
                max_delegations=FAST_DELEGATION_LIMIT,
            )
        if normalized_mode == "deep":
            return cls(
                mode="deep",
                max_total_tokens=dynamic_total_tokens or DEEP_TOTAL_TOKEN_LIMIT,
                max_tool_calls=DEEP_TOOL_CALL_LIMIT,
                max_delegations=DEEP_DELEGATION_LIMIT,
            )
        raise ValueError(f"unsupported run mode: {mode}")

    @classmethod
    def fast(cls) -> "RunBudget":
        return cls.for_mode("fast")

    @classmethod
    def deep(cls) -> "RunBudget":
        return cls.for_mode("deep")

    @property
    def limits(self) -> tuple[int, int, int]:
        return self.max_total_tokens, self.max_tool_calls, self.max_delegations

    @property
    def consumed(self) -> tuple[int, int, int]:
        return self.used_total_tokens, self.used_tool_calls, self.used_delegations

    @property
    def remaining(self) -> tuple[int, int, int]:
        return (
            self.max_total_tokens - self.used_total_tokens,
            self.max_tool_calls - self.used_tool_calls,
            self.max_delegations - self.used_delegations,
        )

    def snapshot(self) -> dict[str, object]:
        return {
            "mode": self.mode,
            "limits": {
                "totalTokens": self.max_total_tokens,
                "toolCalls": self.max_tool_calls,
                "delegations": self.max_delegations,
            },
            "consumed": {
                "totalTokens": self.used_total_tokens,
                "toolCalls": self.used_tool_calls,
                "delegations": self.used_delegations,
            },
            "remaining": {
                "totalTokens": self.remaining[0],
                "toolCalls": self.remaining[1],
                "delegations": self.remaining[2],
            },
        }

    def merge_snapshot(self, snapshot: dict[str, object] | None) -> None:
        if not isinstance(snapshot, dict):
            return
        snapshot_mode = str(snapshot.get("mode") or "").strip().lower()
        if snapshot_mode and snapshot_mode != self.mode:
            raise ValueError(
                f"checkpoint budget mode mismatch: expected={self.mode}, actual={snapshot_mode}"
            )
        consumed = snapshot.get("consumed")
        if not isinstance(consumed, dict):
            return
        self.used_total_tokens = self._merged_consumed(
            self.used_total_tokens,
            consumed.get("totalTokens"),
            self.max_total_tokens,
        )
        self.used_tool_calls = self._merged_consumed(
            self.used_tool_calls,
            consumed.get("toolCalls"),
            self.max_tool_calls,
        )
        self.used_delegations = self._merged_consumed(
            self.used_delegations,
            consumed.get("delegations"),
            self.max_delegations,
        )

    @staticmethod
    def _merged_consumed(current: int, raw: object, limit: int) -> int:
        try:
            restored = max(0, int(raw or 0))
        except (TypeError, ValueError):
            restored = 0
        return max(current, min(restored, limit))

    def consume_tokens(self, count: int) -> None:
        self.used_total_tokens = self._consume(
            "total_tokens",
            count,
            limit=self.max_total_tokens,
            consumed=self.used_total_tokens,
        )

    def require_token_capacity(self) -> None:
        if self.used_total_tokens >= self.max_total_tokens:
            raise BudgetExceededError(
                "total_tokens",
                limit=self.max_total_tokens,
                requested=1,
                consumed=self.used_total_tokens,
            )

    def record_tokens(self, count: int) -> None:
        try:
            self.consume_tokens(count)
        except BudgetExceededError:
            self.used_total_tokens = self.max_total_tokens
            raise

    def consume_tool_call(self, count: int = 1) -> None:
        self.used_tool_calls = self._consume(
            "tool_calls",
            count,
            limit=self.max_tool_calls,
            consumed=self.used_tool_calls,
        )

    def consume_delegation(self, count: int = 1) -> None:
        self.used_delegations = self._consume(
            "delegations",
            count,
            limit=self.max_delegations,
            consumed=self.used_delegations,
        )

    @staticmethod
    def _consume(resource: str, count: int, *, limit: int, consumed: int) -> int:
        if count < 0:
            raise ValueError(f"{resource} count must be non-negative")
        updated = consumed + count
        if updated > limit:
            raise BudgetExceededError(
                resource,
                limit=limit,
                requested=count,
                consumed=consumed,
            )
        return updated


_CURRENT_RUN_BUDGET: ContextVar[RunBudget | None] = ContextVar(
    "harness_run_budget",
    default=None,
)


def current_run_budget() -> RunBudget | None:
    return _CURRENT_RUN_BUDGET.get()


@contextmanager
def run_budget_scope(budget: RunBudget | str = "fast") -> Iterator[RunBudget]:
    scoped_budget = RunBudget.for_mode(budget) if isinstance(budget, str) else budget
    reset_token = _CURRENT_RUN_BUDGET.set(scoped_budget)
    try:
        yield scoped_budget
    finally:
        try:
            _CURRENT_RUN_BUDGET.reset(reset_token)
        except ValueError:
            pass
