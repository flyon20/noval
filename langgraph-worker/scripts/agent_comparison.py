from __future__ import annotations

import asyncio
from collections.abc import Callable
from copy import deepcopy
from dataclasses import asdict
import hashlib
import json
from pathlib import Path
import tempfile
import time
from typing import Any

from app.models.knowledge import BookCandidate, BookProfile, BookResearchPack, ChapterMaterial, KnowledgeSource
from app.services.evaluation.faithfulness import RuleBasedFaithfulnessEvaluator
from app.services.evaluation.golden import GoldenEvalCase, GoldenEvalExpectedTrace
from app.services.evaluation.runner import GoldenEvalRunner
from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.novel_research_agent import NovelResearchAgent
from app.services.provider_client import provider_error_diagnostic


class FixtureResearchBackend:
    """Shared synthetic dossier: no production reads, writes or telemetry."""

    def __init__(self, fixture: dict[str, Any], output_tokens: int) -> None:
        self.fixture = deepcopy(fixture)
        self.output_tokens = output_tokens
        self.calls: list[str] = []

    async def get_agent_runtime_config(self):
        return {"maxFinalOutputTokensFast": self.output_tokens, "maxFinalOutputTokensDeep": self.output_tokens,
                "specialistMcpEnabled": False}

    def _check_scope(self, kwargs: dict[str, Any]) -> None:
        project = self.fixture["project"]
        for key, expected in (("user_id", project["userId"]), ("project_id", project["projectId"]), ("work_id", project["workId"])):
            if kwargs.get(key) is not None and kwargs[key] != expected:
                raise ValueError("synthetic fixture scope mismatch")

    async def resolve_project_work(self, **kwargs):
        self._check_scope(kwargs)
        self.calls.append("project.resolve")
        return {"status": "resolved", **self.fixture["project"]}

    async def retrieve_project_knowledge(self, **kwargs):
        self._check_scope(kwargs)
        self.calls.append("project.retrieve")
        evidence = deepcopy(self.fixture["evidence"])
        start, end = kwargs.get("chapter_from"), kwargs.get("chapter_to")
        evidence = [item for item in evidence if (start is None or item["chapterNo"] >= start) and (end is None or item["chapterNo"] <= end)]
        return {"evidence": evidence[:kwargs.get("limit", 20)], "partial": False, "gaps": [], "diagnostics": {}}

    async def get_project_memory(self, **kwargs):
        self._check_scope(kwargs)
        return {"summary": "", "items": []}

    async def search_evidence(self, **kwargs):
        self._check_scope(kwargs)
        self.calls.append("knowledge.vector_search")
        return [KnowledgeSource(**item, chunkId=item["documentId"], material=item["preview"])
                for item in self.fixture["evidence"][:kwargs.get("limit", 20)]]

    async def search_books(self, **kwargs):
        return [BookCandidate(bookId=101, bookName=self.fixture["project"]["title"], local=True)]

    async def get_book_research_pack(self, **kwargs):
        self._check_scope(kwargs)
        self.calls.append("book.research_pack")
        return BookResearchPack(book=BookProfile(bookId=101, bookName=self.fixture["project"]["title"]),
                                chapters=[ChapterMaterial(bookId=101, chapterId=item["chapterId"], chapterNo=item["chapterNo"],
                                                          title=item["title"], content=item["preview"])
                                          for item in self.fixture["evidence"][:kwargs.get("chapter_limit", 10)]])

    async def lookup_rank(self, **kwargs):
        self.calls.append("rank.lookup")
        return []


class MeasuredProvider:
    """Record actual provider accounting; never log prompts, keys or raw errors."""

    def __init__(self, provider: Any, *, model: str, output_tokens: int, max_calls: int) -> None:
        self.provider = provider
        self.model = model
        self.output_tokens = output_tokens
        self.max_calls = max_calls
        self.calls: list[dict[str, Any]] = []

    def resolve_provider_profile(self, *args, **kwargs):
        return self.provider.resolve_provider_profile(*args, **kwargs)

    async def invoke(self, **kwargs):
        if len(self.calls) >= self.max_calls:
            raise RuntimeError("comparison provider call budget exhausted")
        if kwargs.get("model") != self.model:
            raise ValueError("comparison requires the same model for every phase")
        kwargs["max_tokens"] = min(kwargs.get("max_tokens") or self.output_tokens, self.output_tokens)
        kwargs["temperature"] = 0.2
        envelope = json.dumps({"messages": kwargs.get("messages", []), "tools": kwargs.get("tools", [])}, ensure_ascii=False, sort_keys=True)
        record: dict[str, Any] = {"requestedModel": self.model, "status": "running",
                                  "requestSha256": hashlib.sha256(envelope.encode("utf-8")).hexdigest(),
                                  "messageCount": len(kwargs.get("messages", [])),
                                  "toolSchemaCount": len(kwargs.get("tools", []))}
        self.calls.append(record)
        started = time.perf_counter()
        try:
            result = await self.provider.invoke(**kwargs)
            usage = result.get("usage") if isinstance(result.get("usage"), dict) else {}
            record.update({"status": "succeeded", "actualModel": result.get("model_name"),
                           "tokens": result.get("token_used"), "usage": dict(usage),
                           "outputLimitReached": usage.get("outputTokens", 0) >= kwargs["max_tokens"],
                           "finishReason": result.get("finish_reason") or result.get("stop_reason")})
            return result
        except BaseException as exc:
            record.update({"status": "failed", "errorType": type(exc).__name__, "diagnostic": provider_error_diagnostic(exc)})
            raise
        finally:
            record["durationMs"] = round((time.perf_counter() - started) * 1000)


class _CaptureAgent:
    def __init__(self, agent: NovelResearchAgent) -> None:
        self.agent = agent
        self.response = None

    async def run(self, request):
        self.response = await self.agent.run(request)
        return self.response


async def compare_agents(
    fixture: dict[str, Any], provider: MeasuredProvider, *, case_limit: int = 2,
    turn_timeout: float = 150, total_tokens_per_turn: int = 80_000, tools_per_turn: int = 8,
    runtimes: tuple[str, ...] = ("workflow", "autonomous"), repeats: int = 1,
    answer_length_hint: str = "", pi_node: str | None = None,
    on_result: Callable[[dict[str, Any]], None] | None = None,
    pi_session_dir: Path | None = None,
) -> dict[str, Any]:
    pi_kernel = None
    full_kernels = []
    if any(mode.startswith("pi") for mode in runtimes) and pi_node:
        from app.services.evaluation.pi_kernel import PiEvaluationKernel
        from app.services.harness.agent_kernel import AgentKernel
        pi_kernel = PiEvaluationKernel(AgentKernel(provider), node=pi_node, keep_alive=True)
    try:
        return await _compare_agents(
            fixture, provider, case_limit=case_limit, turn_timeout=turn_timeout,
            total_tokens_per_turn=total_tokens_per_turn, tools_per_turn=tools_per_turn,
            runtimes=runtimes, repeats=repeats, answer_length_hint=answer_length_hint,
            pi_node=pi_node, on_result=on_result, pi_kernel=pi_kernel, pi_session_dir=pi_session_dir,
            full_kernels=full_kernels,
        )
    finally:
        for kernel in full_kernels:
            await kernel.aclose()
        if pi_kernel is not None:
            await pi_kernel.aclose()


async def _compare_agents(
    fixture: dict[str, Any], provider: MeasuredProvider, *, case_limit: int,
    turn_timeout: float, total_tokens_per_turn: int, tools_per_turn: int,
    runtimes: tuple[str, ...], repeats: int, answer_length_hint: str, pi_node: str | None,
    on_result: Callable[[dict[str, Any]], None] | None, pi_kernel: Any, pi_session_dir: Path | None,
    full_kernels: list[Any],
) -> dict[str, Any]:
    if not runtimes or len(set(runtimes)) != len(runtimes) or set(runtimes) - {"workflow", "autonomous", "pi", "pi_context", "pi_full", "pi_full_review"}:
        raise ValueError("select distinct supported runtimes")
    if not 1 <= repeats <= 3:
        raise ValueError("comparison repeats must be between 1 and 3")
    if any(mode.startswith("pi") for mode in runtimes) and not pi_node:
        raise ValueError("Pi comparison requires an explicit Node executable")
    rows = []
    # Reverse ordering across cases/repeats; each arm receives its own actual history.
    cases = fixture["cases"][:case_limit]
    for repeat, case_index, case in ((r, i, c) for r in range(repeats) for i, c in enumerate(cases)):
        order = runtimes if (case_index + repeat) % 2 == 0 else runtimes[::-1]
        histories: dict[str, list[dict[str, str]]] = {mode: [] for mode in order}
        from app.services.harness.autonomous_context import AutonomousConversationContext
        contexts = {mode: AutonomousConversationContext() for mode in order if mode in {"pi_context", "pi_full", "pi_full_review"}}
        session_kernels = {}
        stopped: set[str] = set()
        for turn_index, turn in enumerate(case["turns"]):
            question = turn["question"] + ("\n\n" + answer_length_hint if answer_length_hint else "")
            for mode in order:
                if mode in stopped:
                    continue
                backend = FixtureResearchBackend(fixture, provider.output_tokens)
                agent_kwargs = {"autonomous_context": contexts[mode]} if mode in contexts else {}
                agent = NovelResearchAgent(knowledge_client=backend, provider_client=provider,
                                           runtime_mode="autonomous" if mode.startswith("pi") else mode, **agent_kwargs)
                first_pi_run = 0
                if mode.startswith("pi"):
                    assert pi_kernel is not None
                    active_kernel = pi_kernel
                    if mode.startswith("pi_full"):
                        from app.services.evaluation.pi_session import PiSessionEvaluationKernel
                        if mode not in session_kernels:
                            directory = (pi_session_dir / f"{repeat + 1}-{case_index}-{mode}" if pi_session_dir is not None
                                         else Path(tempfile.mkdtemp(prefix="noval-pi-eval-")))
                            session_kernels[mode] = PiSessionEvaluationKernel(agent.agent_kernel, node=pi_node,
                                session_dir=directory, reviewer=mode == "pi_full_review")
                            full_kernels.append(session_kernels[mode])
                        active_kernel = session_kernels[mode]
                    active_kernel.host = agent.agent_kernel
                    first_pi_run = len(active_kernel.runs)
                    agent.agent_kernel = active_kernel
                    agent.harness.agent_kernel = active_kernel
                capture = _CaptureAgent(agent)
                runner = GoldenEvalRunner(agent=capture, faithfulness_evaluator=RuleBasedFaithfulnessEvaluator())
                request_payload = {**case.get("request", {}), "history": deepcopy(histories[mode]),
                                   "limits": {"modelName": provider.model, "intentModelName": provider.model,
                                              "reviewModelName": provider.model, "timeoutMillis": 60_000},
                                   "conversationId": f"comparison-{repeat + 1}-{case['id']}-{mode}", "reasoningMode": "fast"}
                golden = GoldenEvalCase(case_id=f"{repeat + 1}-{case['id']}-{turn_index + 1}-{mode}", question=question,
                                        request_payload=request_payload,
                                        expected_trace=GoldenEvalExpectedTrace(required_answer_terms=set(turn.get("requiredTerms", [])),
                                                                              require_citations=turn.get("requireCitations", False),
                                                                              require_provider_success=True, forbid_fallback=True))
                first_call = len(provider.calls)
                started = time.perf_counter()
                row: dict[str, Any] = {"repeat": repeat + 1, "caseId": case["id"], "turn": turn_index + 1,
                                       "runtime": mode, "question": question}
                try:
                    budget = RunBudget(mode="fast", max_total_tokens=total_tokens_per_turn, max_tool_calls=tools_per_turn, max_delegations=2)
                    with run_budget_scope(budget):
                        async with asyncio.timeout(turn_timeout):
                            result = await runner.run_case(golden, model_name=provider.model)
                    response = capture.response
                    row.update({"status": response.status, "answer": response.answer, "checks": asdict(result),
                                "sources": [source.model_dump(mode="json", exclude_none=True) for source in response.sources],
                                "selectedSkills": response.resultJson.get("selectedSkills", []),
                                "toolRuns": response.resultJson.get("toolRuns", []),
                                "kernelStopReason": response.resultJson.get("kernelStopReason"), "budget": budget.snapshot()})
                    row["continuationContext"] = response.resultJson.get("continuationContext", {})
                    histories[mode].extend(({"role": "user", "content": question}, {"role": "assistant", "content": response.answer}))
                except Exception as exc:
                    row.update({"status": "error", "errorType": type(exc).__name__})
                    stopped.add(mode)
                finally:
                    await agent.aclose()
                    if mode.startswith("pi_full") and (mode in stopped or turn_index == len(case["turns"]) - 1):
                        await session_kernels[mode].aclose()
                calls = deepcopy(provider.calls[first_call:])
                row.update({"elapsedMs": round((time.perf_counter() - started) * 1000), "providerCalls": calls,
                            "providerCallCount": len(calls), "backendCalls": list(backend.calls),
                            "reportedTokens": sum(call.get("tokens") or 0 for call in calls),
                            "usageComplete": bool(calls) and all(call.get("status") == "succeeded" and call.get("tokens") is not None for call in calls)})
                row["validModelExecution"] = row["usageComplete"] and row["status"] == "answered"
                row.update({"providerElapsedMs": sum(call["durationMs"] for call in calls),
                            "failedProviderCalls": sum(call["status"] != "succeeded" for call in calls),
                            "answerChars": len("".join(row.get("answer", "").split())),
                            "reportedInputTokens": sum(call.get("usage", {}).get("inputTokens", 0) for call in calls),
                            "reportedOutputTokens": sum(call.get("usage", {}).get("outputTokens", 0) for call in calls)})
                if mode.startswith("pi"):
                    row["piRuns"] = deepcopy(active_kernel.runs[first_pi_run:])
                rows.append(row)
                if on_result is not None:
                    on_result(row)
    return {"schemaVersion": 4, "model": provider.model, "fixtureId": fixture["id"], "measurement": "real_provider_with_synthetic_backend",
            "runtimes": list(runtimes), "repeats": repeats, "answerLengthHint": answer_length_hint,
            "historyPolicy": {mode: "native_pi_session_with_scoped_snapshots" if mode.startswith("pi_full") else
                              "scoped_historical_snapshots" if mode == "pi_context" else "user_assistant_only" for mode in runtimes},
            "piTransport": "shared_python_provider_and_domain_tools" if any(mode.startswith("pi") for mode in runtimes) else None,
            "piProcessMode": {mode: "per_case_persistent_agent_session" if mode.startswith("pi_full") else "shared_process_fresh_agent_per_kernel_run"
                              for mode in runtimes if mode.startswith("pi")},
            "limits": {"outputTokensPerCall": provider.output_tokens, "maxProviderCalls": provider.max_calls,
                       "turnTimeoutSeconds": turn_timeout, "totalTokensPerTurn": total_tokens_per_turn, "toolsPerTurn": tools_per_turn},
            "qualityVerdict": "manual_review_required", "rows": rows}
