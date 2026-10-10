"""Full Pi sessions for the local evaluator, with host-owned tools and evidence."""
from __future__ import annotations

from dataclasses import replace
import hashlib
import json
from pathlib import Path
from typing import Any

from app.services.evaluation.pi_kernel import BRIDGE, PiEvaluationKernel
from app.services.harness.agent_kernel import KernelMessage, KernelStopReason, KernelToolCall, KernelToolObservation
from app.services.harness.autonomous_context import AutonomousConversationContext
from app.services.harness.autonomous_research import _ResearchSession, _INSTRUCTIONS, _CONTINUATION_INSTRUCTIONS
from app.services.harness.budget import current_run_budget


READ_SCHEMA = {"type": "function", "function": {"name": "read", "description": "Read an authorized domain SKILL.md from the catalog; other files are unavailable.",
    "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"], "additionalProperties": False}}}
REVIEW_SCHEMA = {"type": "function", "function": {"name": "review_draft", "description": "Ask an independent, read-only reviewer to check a draft against the original task, conversation and retrieved evidence. At most once per user turn.",
    "parameters": {"type": "object", "properties": {"draft": {"type": "string", "minLength": 1, "maxLength": 16000}}, "required": ["draft"], "additionalProperties": False}}}


class PiSessionEvaluationKernel(PiEvaluationKernel):
    bridge_path = BRIDGE.with_name("session-bridge.mjs")
    sdk_name = "@earendil-works/pi-coding-agent"

    def __init__(self, host, *, node: str, session_dir: Path, reviewer: bool = False) -> None:
        super().__init__(host, node=node, keep_alive=True)
        self.session_dir = session_dir.resolve()
        self.session_dir.mkdir(parents=True, exist_ok=True)
        self.reviewer = reviewer
        self.expected_history: str | None = None
        self.scope: str | None = None
        self.skill_digest: str | None = None
        self.had_sources = False
        self.serial = 0
        self.review_owner: _ResearchSession | None = None
        self.review_completed_owner: _ResearchSession | None = None

    async def run(self, request, *, authorization=None, tool_executor=None):
        owner = getattr(tool_executor, "__self__", None)
        if not isinstance(owner, _ResearchSession):
            raise ValueError("Full Pi evaluation requires the scoped research tool executor")
        bound = owner.request
        scope = AutonomousConversationContext._scope(bound)
        history = AutonomousConversationContext._history(bound)
        allowed = self.host.allowed_tool_names(authorization)
        skills = list(owner.skills.values()) if "skill_lookup" in allowed else []
        pins = [skill.trace_pin() for skill in skills]
        digest = hashlib.sha256(json.dumps(pins, sort_keys=True).encode()).hexdigest()
        reuse = (scope is not None and scope == self.scope and history == self.expected_history
                 and digest == self.skill_digest and owner.previous is not None
                 and (not self.had_sources or bool(owner.historical_sources)))
        # Retained tool messages can contain source excerpts. Never restore them after
        # the host rejects history, scope, TTL, skill pins or project reauthorization.
        if not reuse:
            self.serial += 1
        skill_dir = self.session_dir / "skills" / digest
        paths = {}
        for skill in skills:
            path = skill_dir / skill.skillId / "SKILL.md"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("---\nname: " + skill.skillId + "\ndescription: " + json.dumps(skill.description or skill.title, ensure_ascii=False)
                            + "\n---\n\n" + skill.activation_prompt(), encoding="utf-8")
            paths[path.resolve().as_posix().casefold()] = skill.skillId
        schemas = [schema for schema in request.tool_schemas
                   if self.host._schema_name(schema) in allowed and self.host._schema_name(schema) != "skill_lookup"]
        if skills:
            schemas.append(READ_SCHEMA)
        if self.reviewer:
            schemas.append(REVIEW_SCHEMA)

        async def execute(call):
            if call.name == "read":
                skill_id = paths.get(str(call.arguments.get("path", "")).replace("\\", "/").casefold())
                if set(call.arguments) != {"path"} or skill_id is None:
                    return KernelToolObservation(call.id, call.name, "denied", "Only exact authorized skill paths may be read.")
                result = await tool_executor(KernelToolCall(call.id, "skill_lookup", {"skillId": skill_id}))
                return replace(result, name="read")
            if call.name == "review_draft":
                draft = call.arguments.get("draft")
                if self.review_owner is owner or set(call.arguments) != {"draft"} or not isinstance(draft, str) or not 1 <= len(draft) <= 16000:
                    return KernelToolObservation(call.id, call.name, "denied", "Invalid draft or review already used.")
                budget = current_run_budget()
                if budget is not None:
                    if budget.remaining[1] < 1 or budget.remaining[2] < 1:
                        return KernelToolObservation(call.id, call.name, "denied", "Review budget exhausted.")
                    budget.consume_tool_call()
                    budget.consume_delegation()
                self.review_owner = owner
                # Evidence and instructions come from the host, not the delegating model.
                payload = {"question": bound.question, "history": bound.model_dump(mode="json")["history"],
                           "contextSummary": bound.contextSummary,
                           "sources": [source.model_dump(mode="json") for source in owner.sources], "draft": draft}
                owner.tool_runs.append({"name": "review_draft", "toolCallId": call.id,
                                        "status": "authorized", "durationMs": 0})
                return KernelToolObservation(call.id, call.name, "succeeded", json.dumps(payload, ensure_ascii=False, default=str))
            return await tool_executor(call)

        instructions = (_INSTRUCTIONS + _CONTINUATION_INSTRUCTIONS).replace("with skill_lookup", "with read using its exact catalog path")
        if self.reviewer:
            instructions += "\nThis evaluation requires one independent review before final delivery. Call review_draft on your draft, then correct valid findings before the final answer. The review is advice, not evidence."
        incoming = [message for message in request.messages if message.role != "system"]
        if reuse:
            # The full Pi branch already owns the prior messages, tool calls and results.
            incoming = [KernelMessage("user", "Historical evidence authorized for this turn (not current-state verification):\n"
                                      + json.dumps(owner.historical_sources, ensure_ascii=False))] + incoming[-1:]
        self.run_options = {"sessionKey": str(self.serial), "sessionDir": str(self.session_dir),
                            "skillDir": str(skill_dir), "instructions": instructions,
                            "reviewer": self.reviewer, "reviewCompleted": self.review_completed_owner is owner,
                            "resumeHistory": reuse}
        result = await super().run(replace(request, messages=incoming, tool_schemas=schemas),
                                  authorization={"grants": [{"toolName": self.host._schema_name(schema)} for schema in schemas]},
                                  tool_executor=execute)
        outcomes = {item["toolCallId"]: item for item in self.runs[-1].get("reviewResults", [])}
        if any(item["status"] == "succeeded" for item in outcomes.values()):
            self.review_completed_owner = owner
        for record in owner.tool_runs:
            if record.get("toolCallId") in outcomes:
                record.update(outcomes[record["toolCallId"]])
        self.runs[-1]["providerPurposes"] = [call.get("purpose", "answer") for call in result.provider_calls]
        if result.stop_reason == KernelStopReason.COMPLETED and result.content.strip():
            self.scope, self.skill_digest = scope, digest
            self.expected_history = AutonomousConversationContext._history(bound, result.content)
            self.had_sources = bool(owner.sources)
        else:
            self.expected_history = None
        return result
