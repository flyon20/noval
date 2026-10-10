"""Local DSH evaluation using the same host scope, budget and transport as Pi."""
from __future__ import annotations

from pathlib import Path

from app.services.evaluation.pi_session import PiSessionEvaluationKernel


DSH_VERSION = "0.1.0-rc.6"


class DshSessionEvaluationKernel(PiSessionEvaluationKernel):
    # The inherited Python layer enforces host policy. The child process runs
    # DSH's real session/agent/skill plugins, not a replacement Python loop.
    bridge_path = Path(__file__).resolve().parents[3] / "scripts/dsh-eval/session-bridge.mjs"
    sdk_name = "@deepseek-ai/dsh-agent-loop"
    sdk_version = DSH_VERSION

    def __init__(self, host, *, node: str, session_dir: Path) -> None:
        super().__init__(host, node=node, session_dir=session_dir, reviewer=False)
