"""Compare selected research runtimes against one synthetic dossier and real model."""
from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path
import secrets
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--fixture", type=Path, default=Path(__file__).resolve().parents[1] / "tests/fixtures/agent_comparison_v1.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--case-limit", type=int, default=2, choices=(1, 2))
    parser.add_argument("--max-calls", type=int, default=40, choices=range(1, 65), metavar="1..64")
    parser.add_argument("--output-tokens", type=int, default=2048, choices=range(256, 8193), metavar="256..8192")
    parser.add_argument("--turn-timeout", type=int, default=150, choices=range(10, 301), metavar="10..300")
    parser.add_argument("--runtimes", nargs="+", choices=("workflow", "autonomous", "pi", "pi_context", "pi_full", "pi_full_review"), default=["workflow", "autonomous"])
    parser.add_argument("--repeats", type=int, choices=(1, 2, 3), default=1)
    parser.add_argument("--answer-length-hint", default="")
    parser.add_argument("--pi-node", help="Node >=22.19 executable; required when pi is selected")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists; use a fresh path to preserve earlier evidence")
    if len(set(args.runtimes)) != len(args.runtimes):
        parser.error("runtimes must be distinct")
    if any(mode.startswith("pi") for mode in args.runtimes):
        import subprocess
        if not args.pi_node:
            parser.error("--pi-node is required for the Pi runtime")
        bridge_name = "session-bridge.mjs" if any(mode.startswith("pi_full") for mode in args.runtimes) else "bridge.mjs"
        bridge = Path(__file__).resolve().parent / "pi-eval" / bridge_name
        try:
            child_env = {key: value for key, value in os.environ.items()
                         if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "PATHEXT", "TEMP", "TMP", "COMSPEC"}}
            child_env.update({"PI_OFFLINE": "1", "PI_TELEMETRY": "0"})
            probe = subprocess.run([args.pi_node, str(bridge)], input="", capture_output=True, text=True,
                                   timeout=30, env=child_env,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            ready = json.loads(probe.stdout)
            version = tuple(int(part) for part in ready["node"].lstrip("v").split("."))
            if probe.returncode or ready.get("type") != "ready" or ready.get("version") != "0.85.0" or version < (22, 19, 0):
                raise ValueError("unsupported SDK/runtime")
        except (OSError, ValueError, KeyError, subprocess.TimeoutExpired):
            parser.error("Pi preflight failed; install scripts/pi-eval dependencies and use Node >=22.19")
    if not (os.getenv("DEEPSEEK_API_KEY") or os.getenv("AI_OPENAI_COMPATIBLE_API_KEY")):
        parser.error("set DEEPSEEK_API_KEY or AI_OPENAI_COMPATIBLE_API_KEY in the environment")
    # The local fixture has no MCP/backend connection; these required settings are process-local.
    os.environ.setdefault("FASTMCP_INTERNAL_API_KEY", secrets.token_urlsafe(32))
    os.environ.setdefault("MCP_CALL_SIGNING_KEY", secrets.token_urlsafe(32))
    os.environ["AI_LANGGRAPH_CHECKPOINT_BACKEND"] = "memory"
    os.environ["AI_PROVIDER_FALLBACK_MODEL"] = ""
    os.environ["PROVIDER_FALLBACK_MODEL"] = ""
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from scripts.agent_comparison import MeasuredProvider, compare_agents
    from app.services.provider_client import OpenAICompatibleProviderClient

    fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
    provider = MeasuredProvider(OpenAICompatibleProviderClient(), model=args.model,
                                output_tokens=args.output_tokens, max_calls=args.max_calls)

    def progress(row):
        print(json.dumps({key: row[key] for key in ("repeat", "caseId", "turn", "runtime", "status", "elapsedMs", "failedProviderCalls")}),
              file=sys.stderr, flush=True)

    report = asyncio.run(compare_agents(fixture, provider, case_limit=args.case_limit, turn_timeout=args.turn_timeout,
                                        runtimes=tuple(args.runtimes), repeats=args.repeats,
                                        answer_length_hint=args.answer_length_hint, pi_node=args.pi_node, on_result=progress,
                                        pi_session_dir=args.output.parent / (args.output.stem + "-sessions")))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    summary = [{"repeat": row["repeat"], "caseId": row["caseId"], "turn": row["turn"], "runtime": row["runtime"], "status": row["status"],
                "calls": row["providerCallCount"], "tokens": row["reportedTokens"], "elapsedMs": row["elapsedMs"],
                "checks": row.get("checks", {}).get("status"), "usageComplete": row["usageComplete"],
                "errorType": row.get("errorType")} for row in report["rows"]]
    print(json.dumps({"output": str(args.output.resolve()), "results": summary}, ensure_ascii=False))
    expected = sum(len(case["turns"]) * len(args.runtimes) * args.repeats for case in fixture["cases"][:args.case_limit])
    return 0 if len(report["rows"]) == expected and all(row["status"] != "error" for row in report["rows"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
