from __future__ import annotations

import asyncio
import json
import hashlib
import re
import time
from contextlib import aclosing
from datetime import datetime, timezone
from typing import Literal
from urllib.parse import urlsplit
from uuid import uuid4
from collections.abc import AsyncGenerator

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Response
from fastapi.responses import StreamingResponse
import httpx
from pydantic import BaseModel, ConfigDict, Field

from app.models.knowledge import KnowledgeChatRequest
from app.security import verify_internal_api_key
from app.services.checkpointing import MySqlCheckpointConfig
from app.services.evaluation import GoldenEvalRunner, MySqlGoldenEvalRepository
from app.services.evaluation.faithfulness import RuleBasedFaithfulnessEvaluator
from app.services.harness.provider_dispatch_scope import provider_dispatch_scope
from app.services.provider_client import provider_error_diagnostic
from app.services.provider_dialect import canonical_tiers_for, resolve_dialect
from app.services.novel_research_agent import NovelResearchAgent

router = APIRouter(prefix="/internal/knowledge", tags=["knowledge"], dependencies=[Depends(verify_internal_api_key)])

_PROVIDER_PROBE_DEADLINE_SECONDS = 25
_PROVIDER_PROBE_TIMEOUT_MILLIS = 8000
_provider_probe_lock = asyncio.Lock()
research_agent = NovelResearchAgent()
SUPPORTED_EVALUATORS = {"rule-based"}


class AgentEvalRunRequest(BaseModel):
    runId: int | None = None
    suiteName: str = Field(min_length=1, max_length=100)
    runKey: str | None = Field(default=None, max_length=128)
    runnerName: str | None = Field(default=None, max_length=100)
    evaluatorName: str | None = Field(default=None, max_length=100)
    modelName: str | None = Field(default=None, max_length=100)
    caseLimit: int = Field(default=100, ge=1, le=500)
    synchronous: bool = False
    cancelKey: str | None = Field(default=None, max_length=255)
    progressKey: str | None = Field(default=None, max_length=255)


class AgentEvalRunAccepted(BaseModel):
    runId: int
    runKey: str
    suiteName: str
    runnerName: str
    evaluatorName: str
    modelName: str | None = None
    status: str = "RUNNING"
    totalCases: int
    passedCases: int = 0
    failedCases: int = 0
    progressCurrent: int = 0
    progressTotal: int = 0
    progressMessage: str | None = None
    cancelRequested: bool = False
    retryCount: int = 0
    errorMessage: str | None = None
    queued: bool = False


class AgentProviderProbeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    profileKey: str = Field(min_length=1, max_length=128)
    profileVersion: str = Field(min_length=1, max_length=128)


class AgentProviderProbeResult(BaseModel):
    status: Literal["SUCCEEDED", "FAILED"]
    profileKey: str
    profileVersion: str
    endpointFingerprint: str | None = None
    model: str | None = None
    protocol: Literal["responses", "chat_completions"] | None = None
    latencyMillis: int
    usageReported: bool = False
    cacheUsageReported: bool = False
    errorCode: str | None = None


_ERROR_TYPE_TOKEN = re.compile(r"^[A-Za-z0-9_]{1,64}$")


def _chat_failure_message(prefix: str, error: BaseException) -> str:
    """失败文案：前缀之后只带枚举形状的码位，不带任何上游自由文本。

    调用方会把它当成失败原因落库，所以每一段都要是可核实的短 token。
    """
    parts: list[str] = []
    error_type = error.__class__.__name__
    if _ERROR_TYPE_TOKEN.match(error_type):
        parts.append(f"errorType={error_type}")
    diagnostic = provider_error_diagnostic(error)
    if diagnostic:
        parts.append(diagnostic)
    return prefix if not parts else f"{prefix}: {' '.join(parts)}"


@router.post("/chat")
async def run_knowledge_chat(request: KnowledgeChatRequest):
    try:
        response = await research_agent.run(request)
    except (asyncio.CancelledError, HTTPException):
        raise
    except httpx.TimeoutException as exc:
        raise HTTPException(
            status_code=504, detail=_chat_failure_message("knowledge chat failed", exc)
        ) from exc
    except Exception as exc:
        # 默认的 500 只回 text/plain 的 "Internal Server Error"，调用方拿不到码位。
        # 上游拒绝属于网关侧故障，回 502 并带上结构化码位。
        raise HTTPException(
            status_code=502, detail=_chat_failure_message("knowledge chat failed", exc)
        ) from exc
    return response.model_dump()


@router.post(
    "/agent/provider-probe",
    response_model=AgentProviderProbeResult,
    response_model_exclude_none=True,
)
async def probe_agent_provider(
    request: AgentProviderProbeRequest,
    response: Response,
):
    response.headers["Cache-Control"] = "no-store"
    started_at = time.monotonic()
    profile_key = request.profileKey.strip()
    profile_version = request.profileVersion.strip()
    route: dict | None = None
    if _provider_probe_lock.locked():
        return _provider_probe_failure(profile_key, profile_version, started_at, "PROBE_BUSY")
    try:
        async with asyncio.timeout(_PROVIDER_PROBE_DEADLINE_SECONDS):
            async with _provider_probe_lock:
                runtime_config = await research_agent.knowledge_client.get_agent_runtime_config()
                profiles = runtime_config.get("providerProfiles") if isinstance(runtime_config, dict) else None
                route = next(
                    (
                        item
                        for item in profiles or []
                        if isinstance(item, dict)
                        and str(item.get("profileKey") or "").strip() == profile_key
                        and str(item.get("profileVersion") or "").strip() == profile_version
                        and item.get("enabled") is not False
                    ),
                    None,
                )
                if route is None:
                    return _provider_probe_failure(
                        profile_key,
                        profile_version,
                        started_at,
                        "PROFILE_NOT_AVAILABLE",
                    )

                endpoint = str(route.get("endpoint") or "").strip()
                if urlsplit(endpoint).scheme.lower() != "https":
                    return _provider_probe_failure(
                        profile_key,
                        profile_version,
                        started_at,
                        "PROFILE_INSECURE_ENDPOINT",
                        route,
                    )

                dispatch_scope = provider_dispatch_scope(
                    research_agent.knowledge_client.resolve_provider_dispatch,
                    routes=[route],
                    preferred_model=str(route.get("model") or ""),
                )
                async with dispatch_scope as dispatch_scope:
                    dispatch = await dispatch_scope.resolve(
                        profile_key,
                        profile_version,
                        expected_route=route,
                    )
                    provider_profile = research_agent.provider_client.resolve_provider_profile(
                        dispatch.model,
                        base_url=dispatch.endpoint,
                        api_key=dispatch.api_key,
                        protocol=dispatch.protocol,
                        route_snapshot=dispatch.route_snapshot(),
                    )
                    result = await research_agent.provider_client.invoke(
                        messages=[{"role": "user", "content": "Reply with OK."}],
                        model=dispatch.model,
                        temperature=0.0,
                        max_tokens=8,
                        require_json=False,
                        timeout_millis=_PROVIDER_PROBE_TIMEOUT_MILLIS,
                        provider_profile=provider_profile,
                    )

                content = result.get("content") if isinstance(result, dict) else None
                if not isinstance(content, str) or not content.strip():
                    return _provider_probe_failure(
                        profile_key,
                        profile_version,
                        started_at,
                        "RESPONSE_INVALID",
                        route,
                    )

                usage = result.get("usage") if isinstance(result, dict) else None
                safe_usage = usage if isinstance(usage, dict) else {}
                return AgentProviderProbeResult(
                    status="SUCCEEDED",
                    profileKey=profile_key,
                    profileVersion=profile_version,
                    endpointFingerprint=_provider_endpoint_fingerprint(dispatch.endpoint),
                    model=str(result.get("model_name") or dispatch.model),
                    protocol=_provider_probe_protocol(dispatch.protocol),
                    latencyMillis=_provider_probe_latency(started_at),
                    usageReported=safe_usage.get("usageReported") is True,
                    cacheUsageReported=safe_usage.get("cacheUsageReported") is True,
                )
    except httpx.HTTPStatusError as exc:
        status_code = exc.response.status_code if exc.response is not None else 0
        error_code = {
            400: "REQUEST_REJECTED",
            401: "AUTHENTICATION_FAILED",
            402: "QUOTA_EXHAUSTED",
            403: "AUTHENTICATION_FAILED",
            422: "REQUEST_REJECTED",
            429: "RATE_LIMITED",
            500: "PROVIDER_UNAVAILABLE",
            503: "PROVIDER_UNAVAILABLE",
        }.get(status_code, "PROBE_FAILED")
        return _provider_probe_failure(profile_key, profile_version, started_at, error_code, route)
    except (httpx.ConnectError, httpx.TimeoutException, TimeoutError):
        return _provider_probe_failure(
            profile_key,
            profile_version,
            started_at,
            "PROVIDER_UNAVAILABLE",
            route,
        )
    except (TypeError, ValueError):
        return _provider_probe_failure(
            profile_key,
            profile_version,
            started_at,
            "PROFILE_INVALID",
            route,
        )
    except Exception:
        return _provider_probe_failure(
            profile_key,
            profile_version,
            started_at,
            "PROBE_FAILED",
            route,
        )


def _provider_probe_failure(
    profile_key: str,
    profile_version: str,
    started_at: float,
    error_code: str,
    route: dict | None = None,
) -> AgentProviderProbeResult:
    endpoint = str((route or {}).get("endpoint") or "").strip()
    model = str((route or {}).get("model") or "").strip() or None
    protocol = _provider_probe_protocol(str((route or {}).get("protocol") or "").strip())
    return AgentProviderProbeResult(
        status="FAILED",
        profileKey=profile_key,
        profileVersion=profile_version,
        endpointFingerprint=_provider_endpoint_fingerprint(endpoint) if endpoint else None,
        model=model,
        protocol=protocol,
        latencyMillis=_provider_probe_latency(started_at),
        errorCode=error_code,
    )


def _provider_probe_protocol(protocol: str | None) -> Literal["responses", "chat_completions"] | None:
    normalized = str(protocol or "").strip().lower().replace("-", "_")
    if normalized == "responses":
        return "responses"
    if normalized == "chat_completions":
        return "chat_completions"
    return None


def _provider_probe_latency(started_at: float) -> int:
    return max(0, round((time.monotonic() - started_at) * 1000))


def _provider_endpoint_fingerprint(endpoint: str) -> str:
    return hashlib.sha256(endpoint.encode("utf-8")).hexdigest()


@router.get("/runtime-skills")
async def list_runtime_skills():
    return [
        {
            "skillId": skill.skillId,
            "version": skill.version,
            "title": skill.title,
            "description": skill.description,
            "status": skill.status,
            "contentHash": skill.contentHash,
            "source": skill.source,
            "candidateId": skill.candidateId,
            "sourceTraceId": skill.sourceTraceId,
            "intents": [intent.value for intent in skill.intents],
            "triggers": list(skill.triggers),
            "requestedCapabilities": list(skill.requestedCapabilities),
            "skillMetadata": dict(skill.metadata),
            "inputSchema": skill.inputSchema,
            "outputSchema": skill.outputSchema,
        }
        for skill in research_agent.skill_registry.load_all()
    ]


class ProviderTierQueryModel(BaseModel):
    modelKey: str = Field(min_length=1, max_length=200)
    providerType: str | None = Field(default=None, max_length=100)
    modelName: str | None = Field(default=None, max_length=200)


class ProviderTierQuery(BaseModel):
    models: list[ProviderTierQueryModel] = Field(default_factory=list, max_length=200)


@router.post("/provider-tiers")
async def resolve_provider_tiers(request: ProviderTierQuery):
    """Reasoning tiers each registry model may offer, resolved by the dialect table.

    The tier set depends on the model name as well as providerType -- gpt-5 takes
    the reasoning contract while gpt-4o does not -- so callers send the pair and
    the resolution stays here rather than being restated by the backend or the UI.
    """
    entries = []
    for candidate in request.models:
        dialect = resolve_dialect(candidate.providerType, candidate.modelName)
        tiers = canonical_tiers_for(dialect, candidate.modelName)
        entries.append(
            {
                "modelKey": candidate.modelKey,
                "family": dialect.family,
                "supportsReasoning": dialect.emits_reasoning,
                "reasoningTiers": list(tiers),
                "acceptsTemperature": dialect.accepts_temperature,
            }
        )
    return {"models": entries}


@router.post("/eval-runs", response_model=AgentEvalRunAccepted)
async def start_eval_run(request: AgentEvalRunRequest, background_tasks: BackgroundTasks, response: Response):
    evaluator_name = _normalize_evaluator_name(request.evaluatorName)
    repository = MySqlGoldenEvalRepository(MySqlCheckpointConfig.from_settings())
    cases = repository.list_active_cases(request.suiteName, limit=request.caseLimit)
    if not cases:
        raise HTTPException(status_code=400, detail=f"No active eval cases found for suite {request.suiteName}")

    runner_name = request.runnerName or "admin-trigger"
    run_key = request.runKey or f"{request.suiteName}:{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}:{uuid4().hex[:8]}"
    run_id = request.runId or repository.create_run(
        run_key=run_key,
        suite_name=request.suiteName,
        runner_name=runner_name,
        evaluator_name=evaluator_name,
        model_name=request.modelName,
        settings_json={
            "caseCount": len(cases),
            "caseLimit": request.caseLimit,
            "trigger": "admin",
            "synchronous": request.synchronous,
            "cancelKey": request.cancelKey,
            "progressKey": request.progressKey,
        },
        total_cases=len(cases),
    )
    _update_eval_progress(repository, run_id, 0, len(cases), "eval run accepted")
    if request.synchronous:
        result = await _execute_eval_run(
            cases,
            repository,
            run_id,
            request.suiteName,
            run_key,
            runner_name,
            evaluator_name,
            request.modelName,
        )
        status = str(result.get("status") or "running").upper()
        total_cases = int(result.get("totalCases") or len(cases))
        passed_cases = int(result.get("passedCases") or 0)
        failed_cases = int(result.get("failedCases") or 0)
        completed_cases = int(result.get("completedCases") or passed_cases + failed_cases)
        response.status_code = 200
        return AgentEvalRunAccepted(
            runId=run_id,
            runKey=run_key,
            suiteName=request.suiteName,
            runnerName=runner_name,
            evaluatorName=evaluator_name,
            modelName=request.modelName,
            status=status,
            totalCases=total_cases,
            passedCases=passed_cases,
            failedCases=failed_cases,
            progressCurrent=completed_cases if status == "CANCELLED" else total_cases,
            progressTotal=total_cases,
            progressMessage="cancelled" if status == "CANCELLED" else "completed",
            queued=False,
        )
    response.status_code = 202
    background_tasks.add_task(
        _execute_eval_run,
        cases,
        repository,
        run_id,
        request.suiteName,
        run_key,
        runner_name,
        evaluator_name,
        request.modelName,
    )
    return AgentEvalRunAccepted(
        runId=run_id,
        runKey=run_key,
        suiteName=request.suiteName,
        runnerName=runner_name,
        evaluatorName=evaluator_name,
        modelName=request.modelName,
        status="RUNNING",
        totalCases=len(cases),
        progressCurrent=0,
        progressTotal=len(cases),
        progressMessage="queued",
        queued=True,
    )


async def _execute_eval_run(
    cases,
    repository: MySqlGoldenEvalRepository,
    run_id: int,
    suite_name: str,
    run_key: str,
    runner_name: str,
    evaluator_name: str,
    model_name: str | None,
) -> dict:
    runner = GoldenEvalRunner(research_agent, _faithfulness_evaluator(evaluator_name))
    try:
        return await runner.run_suite(
            cases,
            suite_name=suite_name,
            repository=repository,
            persisted_run_id=run_id,
            run_key=run_key,
            runner_name=runner_name,
            evaluator_name=evaluator_name,
            model_name=model_name,
        )
    except Exception as exc:  # pragma: no cover - exercised through production failure path.
        fail_run = getattr(repository, "fail_run", None)
        if callable(fail_run):
            fail_run(run_id=run_id, error_message=str(exc))
        return {
            "runId": run_id,
            "status": "failed",
            "totalCases": len(cases),
            "passedCases": 0,
            "failedCases": len(cases),
            "errorMessage": str(exc),
        }


def _update_eval_progress(repository, run_id: int, current: int, total: int, message: str) -> None:
    update = getattr(repository, "update_run_progress", None)
    if callable(update):
        update(run_id=run_id, current=current, total=total, message=message)


def _normalize_evaluator_name(evaluator_name: str | None) -> str:
    value = (evaluator_name or "rule-based").strip().lower()
    if value not in SUPPORTED_EVALUATORS:
        supported = ", ".join(sorted(SUPPORTED_EVALUATORS))
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported evaluatorName '{evaluator_name}'. Supported evaluators: {supported}",
        )
    return value


def _faithfulness_evaluator(evaluator_name: str) -> RuleBasedFaithfulnessEvaluator:
    if evaluator_name == "rule-based":
        return RuleBasedFaithfulnessEvaluator()
    supported = ", ".join(sorted(SUPPORTED_EVALUATORS))
    raise ValueError(f"Unsupported evaluatorName '{evaluator_name}'. Supported evaluators: {supported}")


@router.post("/chat/stream")
async def run_knowledge_chat_stream(request: KnowledgeChatRequest):
    async def event_stream() -> AsyncGenerator[str, None]:
        try:
            async with aclosing(research_agent.stream(request)) as events:
                async for event in events:
                    yield f"event: {event['event']}\n"
                    yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except asyncio.CancelledError:
            # 客户端断开或运行被取消：不伪造终止事件，原样传下去。
            raise
        except Exception as exc:  # noqa: BLE001 - 见下
            # StreamingResponse 已经把 200 响应头发出去了，异常再也变不成 HTTP 状态码。
            # 不补这条终止事件，调用方只会看到一条被截断的流，
            # 报的是 "stream ended without result"，故障原因整条丢失。
            payload = {
                "event": "error",
                "message": _chat_failure_message("knowledge chat stream failed", exc),
            }
            yield "event: error\n"
            yield f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")
