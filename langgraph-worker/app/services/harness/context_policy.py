"""Run-scoped context governance overrides.

后端把治理项（上下文上限、压缩触发比例、run 预算比例）逐请求放进
``request.limits``。问题是消费这些值的两处代码都拿不到 request：

* ``ContextCompactor.prepare_provider_envelope`` 只有 messages，没有 request；
* ``ContextCompactor`` 自身是进程级单例（``api/knowledge.py`` 的模块级
  ``NovelResearchAgent()`` → ``WebnovelHarness.compose``），构造期读的是环境变量。

所以用一个 run 级 contextvar 把这三个值带下去，形状照抄同目录的
``run_budget_scope``。这样请求层和 provider 信封层看到的是同一组数字，
断点续跑时也不会和 checkpoint 里的快照打架——scope 每次进 run 重新设。
"""

from __future__ import annotations

from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Any


MIN_CONTEXT_WINDOW_TOKENS = 4_096
MAX_CONTEXT_WINDOW_TOKENS = 1_200_000
MIN_COMPACTION_THRESHOLD_PERCENT = 50
MAX_COMPACTION_THRESHOLD_PERCENT = 95
MIN_RUN_TOKEN_BUDGET_PERCENT = 50
MAX_RUN_TOKEN_BUDGET_PERCENT = 400

# 压缩目标比例不单独配置：它跟着触发比例走，保留能力表里原有的档差，
# 这样 ModelContextCapability.__post_init__ 的 target < threshold 不变式恒成立。
MIN_TARGET_RATIO = 0.2
MIN_RATIO_SPREAD = 0.05


@dataclass(frozen=True)
class ContextPolicy:
    max_input_tokens: int | None = None
    compaction_threshold_percent: int | None = None
    run_token_budget_percent: int | None = None

    @property
    def empty(self) -> bool:
        return (
            self.max_input_tokens is None
            and self.compaction_threshold_percent is None
            and self.run_token_budget_percent is None
        )

    @classmethod
    def from_limits(cls, limits: Mapping[str, Any] | None) -> "ContextPolicy":
        source = limits if isinstance(limits, Mapping) else {}
        return cls(
            max_input_tokens=_bounded_int(
                _pick(source, "maxInputTokens", "max_input_tokens"),
                minimum=MIN_CONTEXT_WINDOW_TOKENS,
                maximum=MAX_CONTEXT_WINDOW_TOKENS,
            ),
            compaction_threshold_percent=_bounded_int(
                _pick(source, "compactionThresholdPercent", "compaction_threshold_percent"),
                minimum=MIN_COMPACTION_THRESHOLD_PERCENT,
                maximum=MAX_COMPACTION_THRESHOLD_PERCENT,
            ),
            run_token_budget_percent=_bounded_int(
                _pick(source, "runTokenBudgetPercent", "run_token_budget_percent"),
                minimum=MIN_RUN_TOKEN_BUDGET_PERCENT,
                maximum=MAX_RUN_TOKEN_BUDGET_PERCENT,
            ),
        )

    def window_for(self, default_window: int) -> int:
        """治理值优先，且允许上抬。

        能力表里的 per-model 窗口本来是硬编码猜测——线上 ``gpt-5.6-sol`` 压根不在表里，
        兜底 128k 直接把 run 预算砍到 64k，这才是降级的根因。所以治理值在这里是权威的，
        不再被 ``min(limits, capability)`` 悄悄压回去；越界由后端的 min/max 校验兜。
        """
        if self.max_input_tokens is None:
            return max(MIN_CONTEXT_WINDOW_TOKENS, int(default_window))
        return self.max_input_tokens

    def threshold_ratio_for(self, default_ratio: float) -> float:
        if self.compaction_threshold_percent is None:
            return float(default_ratio)
        return self.compaction_threshold_percent / 100.0

    def target_ratio_for(self, default_threshold_ratio: float, default_target_ratio: float) -> float:
        """按原档差平移目标比例，并夹在合法区间内。"""
        threshold_ratio = self.threshold_ratio_for(default_threshold_ratio)
        if self.compaction_threshold_percent is None:
            return float(default_target_ratio)
        spread = max(MIN_RATIO_SPREAD, float(default_threshold_ratio) - float(default_target_ratio))
        return min(
            max(threshold_ratio - spread, MIN_TARGET_RATIO),
            threshold_ratio - MIN_RATIO_SPREAD,
        )

    def run_budget_tokens(self, context_window_tokens: int, *, default_share: float) -> int:
        share = (
            default_share
            if self.run_token_budget_percent is None
            else self.run_token_budget_percent / 100.0
        )
        return max(1, int(max(1, int(context_window_tokens)) * share))

    def summary(self) -> dict[str, int]:
        payload: dict[str, int] = {}
        if self.max_input_tokens is not None:
            payload["maxInputTokens"] = self.max_input_tokens
        if self.compaction_threshold_percent is not None:
            payload["compactionThresholdPercent"] = self.compaction_threshold_percent
        if self.run_token_budget_percent is not None:
            payload["runTokenBudgetPercent"] = self.run_token_budget_percent
        return payload


_EMPTY_POLICY = ContextPolicy()

_CURRENT_CONTEXT_POLICY: ContextVar[ContextPolicy] = ContextVar(
    "harness_context_policy",
    default=_EMPTY_POLICY,
)


def current_context_policy() -> ContextPolicy:
    return _CURRENT_CONTEXT_POLICY.get() or _EMPTY_POLICY


@contextmanager
def context_policy_scope(policy: ContextPolicy | Mapping[str, Any] | None) -> Iterator[ContextPolicy]:
    scoped = policy if isinstance(policy, ContextPolicy) else ContextPolicy.from_limits(policy)
    reset_token = _CURRENT_CONTEXT_POLICY.set(scoped)
    try:
        yield scoped
    finally:
        try:
            _CURRENT_CONTEXT_POLICY.reset(reset_token)
        except ValueError:
            pass


def _pick(source: Mapping[str, Any], *names: str) -> Any:
    for name in names:
        value = source.get(name)
        if value is not None:
            return value
    return None


def _bounded_int(value: Any, *, minimum: int, maximum: int) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    if parsed <= 0:
        return None
    return min(max(parsed, minimum), maximum)
