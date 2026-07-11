from __future__ import annotations

import json
from datetime import datetime, timezone
from uuid import uuid4
from collections.abc import AsyncGenerator

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Response
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.models.knowledge import KnowledgeChatRequest
from app.security import verify_internal_api_key
from app.services.checkpointing import MySqlCheckpointConfig
from app.services.evaluation import GoldenEvalRunner, MySqlGoldenEvalRepository
from app.services.evaluation.faithfulness import RuleBasedFaithfulnessEvaluator
from app.services.novel_research_agent import NovelResearchAgent

router = APIRouter(prefix="/internal/knowledge", tags=["knowledge"], dependencies=[Depends(verify_internal_api_key)])
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


@router.post("/chat")
async def run_knowledge_chat(request: KnowledgeChatRequest):
    response = await research_agent.run(request)
    return response.model_dump()


@router.get("/runtime-skills")
async def list_runtime_skills():
    return [
        {
            "skillId": skill.skillId,
            "version": skill.version,
            "intents": [intent.value for intent in skill.intents],
            "triggers": list(skill.triggers),
        }
        for skill in research_agent.skill_registry.load_all()
    ]


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
        async for event in research_agent.stream(request):
            yield f"event: {event['event']}\n"
            yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")
