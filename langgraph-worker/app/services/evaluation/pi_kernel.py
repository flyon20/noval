"""Evaluation-only Pi loop. Provider transport and domain enforcement stay in Python."""
from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import subprocess
import time
from typing import Any

from app.services.harness.agent_kernel import (
    AgentKernel, KernelResult, KernelStopReason, KernelToolCall,
    KernelToolObservation, KernelTurnRequest, ToolExecutor,
)
from app.services.harness.cancellation import cancellation_checkpoint, cancellable_await
from app.services.harness.contracts import AuthorizationDecision


PI_VERSION = "0.85.0"
BRIDGE = Path(__file__).resolve().parents[3] / "scripts/pi-eval/bridge.mjs"


class PiEvaluationKernel:
    bridge_path = BRIDGE
    sdk_name = "@earendil-works/pi-agent-core"
    sdk_version = PI_VERSION

    def __init__(self, host: AgentKernel, *, node: str, keep_alive: bool = False) -> None:
        self.host = host
        self.node = node
        self.keep_alive = keep_alive
        self.process: asyncio.subprocess.Process | None = None
        self.runs: list[dict[str, Any]] = []
        self.node_version: str | None = None
        self.run_options: dict[str, Any] = {}

    async def aclose(self) -> None:
        process = self.process
        if process is None:
            return
        if process.stdin is not None:
            process.stdin.close()
        if process.returncode is None:
            try:
                process.terminate()
            except ProcessLookupError:
                pass
        try:
            await asyncio.wait_for(process.wait(), timeout=5)
        except TimeoutError:
            process.kill()
            await process.wait()

    async def run(
        self, request: KernelTurnRequest, *,
        authorization: AuthorizationDecision | dict[str, Any] | None = None,
        tool_executor: ToolExecutor | None = None,
    ) -> KernelResult:
        cancellation_checkpoint()
        allowed = self.host.allowed_tool_names(authorization)
        schemas = self.host._canonical_tool_schemas(
            schema for schema in request.tool_schemas if self.host._schema_name(schema) in allowed
        )
        exposed = {self.host._schema_name(schema) for schema in schemas}
        remaining_tools = max(0, request.max_tool_calls if request.max_tool_calls is not None else 8)
        calls: list[dict[str, Any]] = []
        tool_runs: list[dict[str, Any]] = []
        raw: dict[str, Any] = {}
        forced_stop: KernelStopReason | None = None
        profile = await self.host._resolve_provider_profile(request.model, request.provider_profile)
        self.host._assert_provider_profile_capabilities(
            profile, require_json=request.require_json, reasoning_mode=request.reasoning_mode,
        )
        # Do not pass credentials, NODE_OPTIONS, proxy settings or application config to Pi.
        env = {key: value for key, value in os.environ.items()
               if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "PATHEXT", "TEMP", "TMP", "COMSPEC"}}
        env.update({"PI_OFFLINE": "1", "PI_TELEMETRY": "0"})
        started = time.perf_counter()
        reused = self.process is not None and self.process.returncode is None
        if not reused:
            self.process = await asyncio.create_subprocess_exec(
                self.node, str(self.bridge_path), stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL, env=env, limit=4 * 1024 * 1024,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
        process = self.process
        assert process is not None
        run_info: dict[str, Any] = {"sdk": self.sdk_name, "version": self.sdk_version,
                                    "processReused": reused, "startupMs": 0, "node": self.node_version}
        self.runs.append(run_info)
        completed = False

        async def send(value: dict[str, Any]) -> None:
            assert process.stdin is not None
            process.stdin.write((json.dumps(value, ensure_ascii=False) + "\n").encode("utf-8"))
            await process.stdin.drain()

        try:
            await send({"type": "run", "model": request.model,
                        "messages": [message.to_openai() for message in request.messages],
                        "tools": schemas if remaining_tools and tool_executor else [],
                        "maxTurns": max(1, request.max_turns), "maxTools": remaining_tools,
                        "maxTokens": request.max_tokens or 2048, **self.run_options})
            while True:
                assert process.stdout is not None
                line = await cancellable_await(process.stdout.readline())
                if not line:
                    raise RuntimeError("Pi evaluation process exited without a result")
                event = json.loads(line)
                kind = event.get("type")
                if kind == "ready":
                    if event.get("version") != self.sdk_version:
                        raise RuntimeError(f"{self.sdk_name} evaluation SDK version mismatch")
                    self.node_version = event["node"]
                    run_info.update({"node": event["node"], "startupMs": round((time.perf_counter() - started) * 1000)})
                    continue
                if kind == "fatal":
                    run_info["failureStage"] = str(event.get("stage", "unknown"))[:60]
                    run_info["failureType"] = str(event.get("errorType", "unknown"))[:60]
                    run_info["failureCode"] = str(event.get("errorCode", "unknown"))[:80]
                    raise RuntimeError("Pi evaluation protocol failed")
                if kind == "done":
                    run_info.update(event.get("sessionInfo", {}))
                    messages = [self.host._kernel_message(item) for item in event["messages"]]
                    last = messages[-1] if messages and messages[-1].role == "assistant" else None
                    stop = forced_stop or (KernelStopReason.MAX_TURNS if event["stoppedAtLimit"] else
                                           KernelStopReason.COMPLETED if event["stopReason"] == "stop" else KernelStopReason.ERROR)
                    completed = True
                    return KernelResult(
                        content=last.content if last else "", stop_reason=stop, model_name=request.model,
                        token_used=sum(call["tokenUsed"] for call in calls), tool_runs=tool_runs,
                        messages=messages[:-1] if last else messages, raw=raw, provider_calls=calls,
                    )
                if kind == "model":
                    if len(calls) >= max(1, request.max_turns):
                        raise RuntimeError("Pi model turn budget exceeded")
                    enabled = bool(schemas and remaining_tools and tool_executor)
                    dispatch_schemas = schemas if enabled else []
                    if "toolNames" in event:
                        names = set(event["toolNames"])
                        if not names.issubset(exposed):
                            raise RuntimeError("Pi exposed an unauthorized tool schema")
                        dispatch_schemas = [schema for schema in dispatch_schemas if self.host._schema_name(schema) in names]
                        enabled = bool(dispatch_schemas)
                    self.host._assert_provider_profile_capabilities(profile, tools=enabled)
                    working, _ = self.host._prepare_provider_messages(
                        [self.host._kernel_message(item) for item in event["messages"]],
                        request=request, tool_schemas=dispatch_schemas,
                    )
                    kwargs = {"messages": [message.to_openai() for message in working], "model": request.model,
                              "temperature": request.temperature, "max_tokens": request.max_tokens,
                              "require_json": request.require_json, "reasoning_mode": request.reasoning_mode,
                              "reasoning_effort": request.reasoning_effort, "timeout_millis": request.timeout_millis,
                              "cache_affinity": request.cache_affinity, "request_family": request.request_family}
                    if enabled:
                        kwargs["tools"] = dispatch_schemas
                    call_started = time.perf_counter()
                    raw, profile, attempt = await self.host._invoke_provider_with_retry_and_failover(kwargs, profile)
                    reply = self.host._parse_model_reply(raw, fallback_model=request.model)
                    calls.append({"kernelTurn": len(calls) + 1, "transport": "invoke", "status": "succeeded",
                                  "purpose": event.get("purpose", "answer"),
                                  "model": reply.model_name, "tokenUsed": reply.token_used,
                                  "durationMs": round((time.perf_counter() - call_started) * 1000),
                                  "toolCallCount": len(reply.tool_calls), "usage": raw.get("usage", {}),
                                  **attempt.trace_summary()})
                    dispatched_names = {self.host._schema_name(schema) for schema in dispatch_schemas}
                    if any(call.name not in dispatched_names for call in reply.tool_calls):
                        forced_stop = KernelStopReason.UNAUTHORIZED_TOOL
                    finish = raw.get("finish_reason") or raw.get("stop_reason")
                    stop_reason = "error" if forced_stop else "length" if finish in {"length", "max_tokens"} else "toolUse" if reply.tool_calls else "stop"
                    result = {"content": reply.content, "reasoningContent": reply.reasoning_content,
                              "toolCalls": [{"id": call.id, "name": call.name, "arguments": call.arguments} for call in reply.tool_calls],
                              "tokens": reply.token_used, "usage": raw.get("usage", {}), "stopReason": stop_reason}
                elif kind == "tool":
                    call = KernelToolCall(**event["call"])
                    cancellation_checkpoint()
                    if call.name not in exposed:
                        raise RuntimeError("Pi requested an unauthorized tool")
                    if not remaining_tools or tool_executor is None:
                        observation = KernelToolObservation(call.id, call.name, "failed", "tool budget exhausted before execution")
                    else:
                        observation = None
                        if self.host.before_tool is not None:
                            observation = await self.host._maybe_await(self.host.before_tool(call, None))
                            if observation is None:
                                forced_stop = KernelStopReason.HOOK_BLOCKED
                                observation = KernelToolObservation(call.id, call.name, "denied", "host hook blocked tool")
                        if observation is None:
                            observation = await tool_executor(call)
                        if self.host.after_tool is not None:
                            hooked = await self.host._maybe_await(self.host.after_tool(call, observation))
                            if isinstance(hooked, KernelToolObservation):
                                observation = hooked
                        remaining_tools -= 1
                    tool_runs.append(self.host._tool_run_dict(observation))
                    result = {"content": observation.content, "status": observation.status}
                else:
                    raise RuntimeError("Unknown Pi evaluation event")
                await send({"type": "reply", "id": event["id"], "result": result})
        finally:
            if not self.keep_alive or not completed:
                await self.aclose()
            run_info["elapsedMs"] = round((time.perf_counter() - started) * 1000)
