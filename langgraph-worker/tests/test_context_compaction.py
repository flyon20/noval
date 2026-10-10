from __future__ import annotations

import json
import unittest

from app.models.knowledge import KnowledgeChatRequest
from app.services.harness.context_compaction import (
    canonical_provider_envelope,
    canonical_provider_request_envelope,
    ContextCompactor,
    ModelContextCapability,
    ModelContextCapabilityRegistry,
    estimate_provider_input_tokens,
    estimate_text_tokens,
)
from app.services.harness.context_policy import context_policy_scope


def capability(**overrides: object) -> ModelContextCapability:
    values: dict[str, object] = {
        "context_window_tokens": 5_000,
        "max_output_tokens": 2_000,
        "compaction_threshold_ratio": 0.85,
        "reserved_output_tokens": 300,
        "safety_margin_tokens": 200,
        "target_ratio": 0.62,
        "minimum_recent_turns": 2,
        "max_summary_tokens": 900,
    }
    values.update(overrides)
    return ModelContextCapability(**values)


def long_history(turns: int = 8, chars: int = 300) -> list[dict[str, str]]:
    history: list[dict[str, str]] = []
    for index in range(turns):
        constraint = "必须保留主角不伤害普通人的约束。" if index == 0 else ""
        history.append({
            "role": "user",
            "content": f"第{index + 1}轮用户目标：{constraint}" + ("设定" * chars),
        })
        history.append({
            "role": "assistant",
            "content": f"第{index + 1}轮可见结论：" + ("方案" * chars),
        })
    return history


class ContextCompactionTests(unittest.TestCase):
    def test_registry_unifies_default_window_and_accepts_future_models(self) -> None:
        registry = ModelContextCapabilityRegistry.from_json("""
            {
              "gpt-future": {"contextWindowTokens": 400000, "reservedOutputTokens": 12000},
              "kimi-future": {"contextWindowTokens": 256000, "compactionThresholdRatio": 0.9}
            }
            """)

        # 换模型不该换窗口：兜底和 deepseek 现在同为 300k，治理项统一管住上限。
        self.assertEqual(300_000, registry.resolve("deepseek-v4-flash").context_window_tokens)
        self.assertEqual(300_000, registry.resolve("deepseek-v4-pro").context_window_tokens)
        self.assertEqual(300_000, registry.resolve("deepseek-chat").context_window_tokens)
        self.assertEqual(300_000, registry.resolve("deepseek-reasoner").context_window_tokens)
        self.assertEqual(400_000, registry.resolve("gpt-future").context_window_tokens)
        self.assertEqual(12_000, registry.resolve("gpt-future").reserved_output_tokens)
        self.assertEqual(256_000, registry.resolve("kimi-future").context_window_tokens)
        self.assertAlmostEqual(0.9, registry.resolve("kimi-future").compaction_threshold_ratio)
        self.assertEqual(300_000, registry.resolve("unknown-future-model").context_window_tokens)

    def test_run_policy_overrides_window_and_ratios_for_both_layers(self) -> None:
        compactor = ContextCompactor()

        # 线上降级的形状：模型不在能力表里。治理值必须能上抬，而不是被 min() 压回去。
        self.assertEqual(300_000, compactor.capability_for("gpt-5.6-sol").context_window_tokens)
        with context_policy_scope({
            "maxInputTokens": 400_000,
            "compactionThresholdPercent": 70,
        }):
            scoped = compactor.capability_for("gpt-5.6-sol")
            self.assertEqual(400_000, scoped.context_window_tokens)
            self.assertAlmostEqual(0.70, scoped.compaction_threshold_ratio)
            # 目标比例按原档差（0.85-0.62=0.23）同步下移，不变式仍成立。
            self.assertAlmostEqual(0.47, scoped.target_ratio)
            self.assertLess(scoped.target_ratio, scoped.compaction_threshold_ratio)
        self.assertEqual(300_000, compactor.capability_for("gpt-5.6-sol").context_window_tokens)

    def test_run_policy_keeps_target_ratio_legal_at_both_extremes(self) -> None:
        compactor = ContextCompactor()
        for percent in (50, 95):
            with context_policy_scope({"compactionThresholdPercent": percent}):
                scoped = compactor.capability_for("deepseek-v4-pro")
                self.assertAlmostEqual(percent / 100.0, scoped.compaction_threshold_ratio)
                self.assertGreaterEqual(scoped.target_ratio, 0.2)
                self.assertLess(scoped.target_ratio, scoped.compaction_threshold_ratio)

    def test_request_limits_window_reaches_provider_envelope_layer(self) -> None:
        # provider 信封层拿不到 request，只能靠 run scope 看到治理值。
        compactor = ContextCompactor()
        request = KnowledgeChatRequest(question="q", history=[], limits={"maxInputTokens": 300_000})
        with context_policy_scope(request.limits):
            envelope_window = compactor.capability_for("gpt-5.6-sol").context_window_tokens
            request_window = compactor.context_window_for(request, model="gpt-5.6-sol")
        self.assertEqual(300_000, envelope_window)
        self.assertEqual(envelope_window, request_window)

    def test_estimator_is_conservative_for_cjk(self) -> None:
        self.assertGreater(
            estimate_text_tokens("网文设定" * 100),
            estimate_text_tokens("web novel" * 100),
        )

    def test_canonical_envelope_counts_context_and_tool_result_surface(self) -> None:
        base = KnowledgeChatRequest(question="question", history=[])
        expanded = KnowledgeChatRequest(
            question="question",
            history=[
                {"role": "assistant", "content": "tool call", "tool_call_id": "call-1"},
                {"role": "tool", "content": "result" * 500, "tool_call_id": "call-1"},
            ],
            contextBundle={"evidence": ["evidence" * 200]},
        )

        envelope = canonical_provider_envelope(expanded, model="deepseek-chat")

        self.assertEqual("deepseek-chat", envelope["model"])
        self.assertEqual("tool", envelope["messages"][1]["role"])
        self.assertGreater(
            estimate_text_tokens(json.dumps(envelope, ensure_ascii=False)),
            estimate_text_tokens(json.dumps(canonical_provider_envelope(base), ensure_ascii=False)),
        )

    def test_final_provider_envelope_counts_authorized_tool_schema(self) -> None:
        messages = [
            {"role": "system", "content": "stable prefix"},
            {"role": "user", "content": "question"},
        ]
        tools = [{
            "type": "function",
            "function": {
                "name": "rank.lookup",
                "description": "schema detail " * 500,
                "parameters": {"type": "object"},
            },
        }]

        envelope = canonical_provider_request_envelope(
            messages,
            model="deepseek-chat",
            tool_schemas=tools,
            reasoning_mode="deep",
        )

        self.assertEqual(tools, envelope["tools"])
        self.assertGreater(
            estimate_provider_input_tokens(
                messages,
                model="deepseek-chat",
                tool_schemas=tools,
                reasoning_mode="deep",
            ),
            estimate_provider_input_tokens(messages, model="deepseek-chat"),
        )

    def test_provider_surface_metadata_is_deterministic_and_redacted(self) -> None:
        messages = [
            {"role": "system", "content": "stable prefix"},
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [{"id": "call-1", "type": "function"}],
            },
            {"role": "tool", "tool_call_id": "call-1", "content": "secret result"},
            {"role": "user", "content": "current question"},
        ]
        tools = [
            {"type": "function", "function": {"name": "zeta", "parameters": {}}},
            {"type": "function", "function": {"name": "alpha", "parameters": {}}},
        ]
        compactor = ContextCompactor(capability())
        first = compactor.prepare_provider_envelope(
            messages,
            model="unit-model",
            tool_schemas=tools,
            max_output_tokens=512,
        )
        reordered = compactor.prepare_provider_envelope(
            messages,
            model="unit-model",
            tool_schemas=list(reversed(tools)),
            max_output_tokens=512,
        )

        summary = first.trace_summary()
        self.assertEqual("not_needed", summary["status"])
        self.assertEqual(summary["beforeSurfaceFingerprint"], summary["afterSurfaceFingerprint"])
        self.assertTrue(summary["beforeSurfaceFingerprint"].startswith("sha256:"))
        self.assertEqual(summary["beforeSurfaceFingerprint"], reordered.trace_summary()["beforeSurfaceFingerprint"])
        self.assertEqual(4, summary["beforeMessageCount"])
        self.assertEqual(4, summary["afterMessageCount"])
        self.assertEqual(1, summary["beforeToolCallCount"])
        self.assertEqual(1, summary["afterToolCallCount"])
        self.assertEqual(1, summary["beforeToolResultCount"])
        self.assertEqual(1, summary["afterToolResultCount"])
        self.assertEqual(2, summary["toolSchemaCount"])
        self.assertTrue(summary["bodyRedacted"])
        self.assertNotIn("secret result", json.dumps(summary, ensure_ascii=False))

    def test_final_provider_envelope_prunes_tool_result_and_preserves_pair(self) -> None:
        messages = [
            {"role": "system", "content": "stable prefix"},
            {"role": "user", "content": "old request"},
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [{"id": "call-1", "type": "function"}],
            },
            {
                "role": "tool",
                "tool_call_id": "call-1",
                "content": "large-result " * 5000,
            },
            {"role": "user", "content": "recent request"},
        ]

        result = ContextCompactor(capability(
            context_window_tokens=12_000,
            target_ratio=0.72,
            minimum_recent_turns=2,
        )).prepare_provider_envelope(
            messages,
            model="unit-model",
            max_output_tokens=512,
        )

        roles = [message.get("role") for message in result.messages]
        tool_message = next(message for message in result.messages if message.get("role") == "tool")
        assistant_message = next(
            message for message in result.messages if message.get("role") == "assistant"
        )
        self.assertEqual("compacted", result.status)
        self.assertLess(result.after_input_tokens, result.before_input_tokens)
        self.assertLessEqual(result.after_input_tokens, result.target_tokens)
        summary = result.trace_summary()
        self.assertNotEqual(summary["beforeSurfaceFingerprint"], summary["afterSurfaceFingerprint"])
        self.assertEqual(5, summary["beforeMessageCount"])
        self.assertEqual(summary["afterMessageCount"], len(result.messages))
        self.assertEqual(1, summary["beforeToolCallCount"])
        self.assertEqual(1, summary["afterToolCallCount"])
        self.assertEqual(1, summary["beforeToolResultCount"])
        self.assertEqual(1, summary["afterToolResultCount"])
        self.assertTrue(summary["bodyRedacted"])
        self.assertNotIn("large-result", json.dumps(summary, ensure_ascii=False))
        self.assertIn("assistant", roles)
        self.assertIn("tool", roles)
        self.assertEqual("call-1", assistant_message["tool_calls"][0]["id"])
        self.assertEqual("call-1", tool_message["tool_call_id"])
        self.assertLess(len(tool_message["content"]), len(messages[3]["content"]))

    def test_provider_envelope_compaction_is_replay_equal_for_same_input(self) -> None:
        messages = [
            {"role": "system", "content": "stable prefix"},
            {"role": "user", "content": "old request"},
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [{"id": "call-1", "type": "function"}],
            },
            {
                "role": "tool",
                "tool_call_id": "call-1",
                "content": "large-result " * 5000,
            },
            {"role": "user", "content": "recent request"},
        ]
        original = json.loads(json.dumps(messages, ensure_ascii=False))
        compactor = ContextCompactor(capability(
            context_window_tokens=12_000,
            target_ratio=0.72,
            minimum_recent_turns=2,
        ))

        first = compactor.prepare_provider_envelope(
            messages,
            model="unit-model",
            max_output_tokens=512,
        )
        replay = compactor.prepare_provider_envelope(
            messages,
            model="unit-model",
            max_output_tokens=512,
        )

        self.assertEqual("compacted", first.status)
        self.assertEqual(first.messages, replay.messages)
        self.assertEqual(first.trace_summary(), replay.trace_summary())
        self.assertEqual(original, messages)
        self.assertEqual(
            first.trace_summary()["afterSurfaceFingerprint"],
            replay.trace_summary()["afterSurfaceFingerprint"],
        )

    def test_final_provider_envelope_fails_when_current_surface_cannot_converge(self) -> None:
        messages = [
            {"role": "system", "content": "immutable " * 5000},
            {"role": "user", "content": "current " * 5000},
        ]
        result = ContextCompactor(capability(
            context_window_tokens=8_000,
            minimum_recent_turns=1,
        )).prepare_provider_envelope(
            messages,
            model="unit-model",
            max_output_tokens=512,
        )

        self.assertEqual("failed", result.status)
        self.assertEqual("compaction_not_converged", result.reason)
        self.assertEqual(messages, result.messages)
        summary = result.trace_summary()
        self.assertEqual(summary["beforeInputTokens"], summary["afterInputTokens"])
        self.assertEqual(summary["beforeSurfaceFingerprint"], summary["afterSurfaceFingerprint"])
        self.assertEqual(summary["beforeMessageCount"], summary["afterMessageCount"])
        self.assertEqual(summary["beforeToolCallCount"], summary["afterToolCallCount"])
        self.assertEqual(summary["beforeToolResultCount"], summary["afterToolResultCount"])
        self.assertTrue(summary["bodyRedacted"])
        self.assertNotIn("immutable", json.dumps(summary, ensure_ascii=False))

    def test_short_request_does_not_compact(self) -> None:
        request = KnowledgeChatRequest(
            question="继续优化前三章",
            history=[{"role": "user", "content": "保持上一轮方向"}],
        )

        result = ContextCompactor(capability()).prepare(request, model="unit-model")

        self.assertEqual("not_needed", result.status)
        self.assertIs(request, result.request)
        self.assertEqual([], result.events())

    def test_near_limit_request_compacts_old_turns_and_keeps_recent_turns(self) -> None:
        history = long_history()
        request = KnowledgeChatRequest(
            question="继续完成当前方案",
            history=history,
            contextBundle={
                "threadSummary": {
                    "scope": "thread",
                    "content": {"history": history},
                }
            },
        )

        result = ContextCompactor(capability(
            context_window_tokens=12_000,
            target_ratio=0.72,
        )).prepare(request, model="unit-model")

        self.assertEqual("compacted", result.status)
        self.assertLess(result.after_input_tokens, result.before_input_tokens)
        self.assertEqual(history[-4:], result.request.history[-4:])
        self.assertIn("必须保留主角不伤害普通人的约束", result.compacted_summary or "")
        self.assertGreater(result.summarized_message_count, 0)
        self.assertGreaterEqual(result.retained_turn_count, 2)
        self.assertEqual(
            result.request.history,
            result.request.contextBundle["threadSummary"]["content"]["history"],
        )
        self.assertEqual(
            ["context_compacting", "context_compacted"],
            [event["event"] for event in result.events()],
        )
        self.assertNotIn("compactedSummary", result.events()[-1])

    def test_persisted_coverage_is_reused_without_recompacting_same_messages(self) -> None:
        history = long_history()
        compactor = ContextCompactor(capability(
            context_window_tokens=12_000,
            target_ratio=0.72,
        ))
        first = compactor.prepare(
            KnowledgeChatRequest(question="继续", history=history),
            model="unit-model",
        )
        replay = KnowledgeChatRequest(
            question="再继续",
            contextSummary=first.compacted_summary,
            history=history,
        )

        second = compactor.prepare(replay, model="unit-model")

        self.assertIn(second.status, {"reused", "not_needed"})
        self.assertNotEqual("compacted", second.status)
        self.assertLess(len(second.request.history), len(history))
        self.assertEqual([], second.events())

    def test_tool_call_and_output_are_not_split_across_retained_history(self) -> None:
        history = [
            {"role": "user", "content": "旧任务" + ("甲" * 900)},
            {"role": "assistant", "content": "tool_call:lookup", "tool_call_id": "call-1"},
            {"role": "tool", "content": "tool_output:result", "tool_call_id": "call-1"},
            {"role": "assistant", "content": "旧任务结论" + ("乙" * 900)},
            {"role": "user", "content": "最近任务"},
            {"role": "assistant", "content": "最近结论"},
        ]
        result = ContextCompactor(capability(minimum_recent_turns=1)).prepare(
            KnowledgeChatRequest(question="继续", history=history),
            model="unit-model",
        )

        retained = "\n".join(str(item) for item in result.request.history)
        self.assertEqual("tool_call:lookup" in retained, "tool_output:result" in retained)

    def test_oversized_tool_result_is_pruned_before_compaction_is_reported(self) -> None:
        history = [
            {"role": "user", "content": "old request"},
            {"role": "assistant", "content": "tool call", "tool_call_id": "call-1"},
            {"role": "tool", "content": "large-result " * 5000, "tool_call_id": "call-1"},
            {"role": "user", "content": "recent request"},
            {"role": "assistant", "content": "recent answer"},
        ]

        result = ContextCompactor(capability(
            context_window_tokens=12_000,
            target_ratio=0.72,
            minimum_recent_turns=2,
        )).prepare(
            KnowledgeChatRequest(question="continue", history=history),
            model="unit-model",
        )

        tool_messages = [item for item in result.request.history if item.get("role") == "tool"]
        self.assertEqual("compacted", result.status)
        self.assertTrue(tool_messages)
        self.assertLess(len(tool_messages[0]["content"]), len(history[2]["content"]))
        retained = "\n".join(str(item) for item in result.request.history)
        self.assertEqual("tool_call" in retained, "large-result" in retained)

    def test_non_shrinking_candidate_is_not_marked_compacted(self) -> None:
        class NonShrinkingCompactor(ContextCompactor):
            def _candidate_request(self, request, turns, keep_start, capability):
                return request, "summary", set(), 1

            def _fit_history(self, history, target_tokens):
                return history

        request = KnowledgeChatRequest(question="continue", history=long_history(chars=500))
        result = NonShrinkingCompactor(capability(context_window_tokens=12_000)).prepare(
            request,
            model="unit-model",
        )

        self.assertEqual("failed", result.status)
        self.assertEqual("compaction_not_converged", result.reason)
        self.assertIs(request, result.request)

    def test_compaction_failure_keeps_original_request(self) -> None:
        class FailingCompactor(ContextCompactor):
            def _build_summary(self, *args: object, **kwargs: object) -> str:
                raise RuntimeError("summary failed")

        request = KnowledgeChatRequest(question="继续", history=long_history())

        result = FailingCompactor(capability()).prepare(request, model="unit-model")

        self.assertEqual("failed", result.status)
        self.assertIs(request, result.request)
        self.assertEqual("compaction_failed", result.reason)
        self.assertEqual([], result.events())


if __name__ == "__main__":
    unittest.main()
