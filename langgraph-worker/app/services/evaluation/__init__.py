from __future__ import annotations

from importlib import import_module
from typing import Any


_EXPORTS = {
    "GoldenEvalCase": ("app.services.evaluation.golden", "GoldenEvalCase"),
    "GoldenEvalCaseResult": ("app.services.evaluation.golden", "GoldenEvalCaseResult"),
    "GoldenEvalExpectedTrace": ("app.services.evaluation.golden", "GoldenEvalExpectedTrace"),
    "GoldenEvalRunner": ("app.services.evaluation.runner", "GoldenEvalRunner"),
    "MySqlGoldenEvalRepository": ("app.services.evaluation.repository", "MySqlGoldenEvalRepository"),
    "RetrievalEvalThresholds": ("app.services.retrieval_eval", "RetrievalEvalThresholds"),
    "RuleBasedFaithfulnessEvaluator": (
        "app.services.evaluation.faithfulness",
        "RuleBasedFaithfulnessEvaluator",
    ),
    "source_eval_id": ("app.services.evaluation.golden", "source_eval_id"),
}

__all__ = list(_EXPORTS)


def __getattr__(name: str) -> Any:
    target = _EXPORTS.get(name)
    if target is None:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    module_name, attribute_name = target
    value = getattr(import_module(module_name), attribute_name)
    globals()[name] = value
    return value


def __dir__() -> list[str]:
    return sorted(set(globals()) | set(__all__))
