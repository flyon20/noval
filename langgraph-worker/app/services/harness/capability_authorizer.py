from __future__ import annotations

from uuid import uuid4

from .contracts import (
    AuthorizationDecision,
    CapabilityPlan,
    SideEffectPolicy,
    ToolGrant,
)


# Static capability -> local/MCP tool grants. requested capabilities never expand beyond this map.
_CAPABILITY_TOOL_MAP: dict[str, tuple[tuple[str, SideEffectPolicy, bool], ...]] = {
    "market.read": (
        ("rank.lookup", SideEffectPolicy.READ_ONLY, True),
    ),
    "market.research": (
        ("rank.research_pack", SideEffectPolicy.READ_ONLY, True),
        ("knowledge.vector_search", SideEffectPolicy.READ_ONLY, True),
    ),
    "market.refresh": (
        ("rank.refresh", SideEffectPolicy.CONFIRMED_WRITE, True),
    ),
    "book.read": (
        ("book.search", SideEffectPolicy.READ_ONLY, True),
        ("book.research_pack", SideEffectPolicy.READ_ONLY, True),
        ("knowledge.vector_search", SideEffectPolicy.READ_ONLY, True),
    ),
    "project.resolve": (
        ("project.resolve", SideEffectPolicy.READ_ONLY, True),
    ),
    "project.retrieve": (
        ("project.resolve", SideEffectPolicy.READ_ONLY, True),
        ("project.retrieve", SideEffectPolicy.READ_ONLY, True),
        ("project.foreshadowing.list", SideEffectPolicy.READ_ONLY, True),
        ("project.foreshadowing.aggregate", SideEffectPolicy.READ_ONLY, True),
        ("project.timeline_lookup", SideEffectPolicy.READ_ONLY, True),
        ("project.character_state_lookup", SideEffectPolicy.READ_ONLY, True),
        ("project.world_rule_lookup", SideEffectPolicy.READ_ONLY, True),
    ),
    "project.continuity.read": (
        ("project.resolve", SideEffectPolicy.READ_ONLY, True),
        ("project.retrieve", SideEffectPolicy.READ_ONLY, True),
        ("project.foreshadowing.list", SideEffectPolicy.READ_ONLY, True),
        ("project.foreshadowing.aggregate", SideEffectPolicy.READ_ONLY, True),
        ("project.timeline_lookup", SideEffectPolicy.READ_ONLY, True),
        ("project.character_state_lookup", SideEffectPolicy.READ_ONLY, True),
        ("project.world_rule_lookup", SideEffectPolicy.READ_ONLY, True),
    ),
    "memory.project.read": (
        ("memory.project_context", SideEffectPolicy.READ_ONLY, True),
    ),
    "skill.activate": (
        ("skill.lookup", SideEffectPolicy.READ_ONLY, True),
    ),
    "creation.opening": (
        ("skill.lookup", SideEffectPolicy.READ_ONLY, True),
        ("knowledge.vector_search", SideEffectPolicy.READ_ONLY, True),
        ("memory.project_context", SideEffectPolicy.READ_ONLY, True),
    ),
    "creation.outline": (
        ("skill.lookup", SideEffectPolicy.READ_ONLY, True),
        ("memory.project_context", SideEffectPolicy.READ_ONLY, True),
    ),
    "creation.chapter_outline": (
        ("skill.lookup", SideEffectPolicy.READ_ONLY, True),
        ("memory.project_context", SideEffectPolicy.READ_ONLY, True),
    ),
    "creation.ideation": (
        ("skill.lookup", SideEffectPolicy.READ_ONLY, True),
        ("knowledge.vector_search", SideEffectPolicy.READ_ONLY, True),
    ),
    "creation.character": (
        ("skill.lookup", SideEffectPolicy.READ_ONLY, True),
        ("memory.project_context", SideEffectPolicy.READ_ONLY, True),
    ),
    "creation.worldbuilding": (
        ("skill.lookup", SideEffectPolicy.READ_ONLY, True),
        ("memory.project_context", SideEffectPolicy.READ_ONLY, True),
    ),
    "creation.revision": (
        ("knowledge.vector_search", SideEffectPolicy.READ_ONLY, True),
        ("editor.risk_check", SideEffectPolicy.READ_ONLY, True),
        ("reader.simulate_feedback", SideEffectPolicy.READ_ONLY, True),
    ),
    "creation.followup": (
        ("memory.project_context", SideEffectPolicy.READ_ONLY, True),
    ),
    "review.editor": (
        ("editor.risk_check", SideEffectPolicy.READ_ONLY, True),
    ),
    "review.reader": (
        ("reader.simulate_feedback", SideEffectPolicy.READ_ONLY, True),
    ),
}


class CapabilityAuthorizer:
    """Sole source of tool visibility for TaskGraph / ToolCallLoop."""

    VERSION = "capability-authorizer-v1"

    def authorize(
        self,
        plan: CapabilityPlan,
    ) -> AuthorizationDecision:
        capability_ids = [
            request.capabilityId
            for request in plan.capabilityRequests
        ]
        capability_ids.extend(plan.requestedToolCapabilities)
        # stable unique
        seen: set[str] = set()
        ordered_caps: list[str] = []
        for cap in capability_ids:
            if cap and cap not in seen:
                seen.add(cap)
                ordered_caps.append(cap)

        grants: list[ToolGrant] = []
        denied: list[str] = []
        reasons: list[str] = [f"authorizer:{self.VERSION}"]
        granted_tools: set[str] = set()

        for capability_id in ordered_caps:
            mapped = _CAPABILITY_TOOL_MAP.get(capability_id)
            if not mapped:
                denied.append(capability_id)
                reasons.append(f"unmapped_capability:{capability_id}")
                continue
            reasons.append(f"capability:{capability_id}")
            for tool_name, side_effect, idempotent in mapped:
                if tool_name in granted_tools:
                    continue
                granted_tools.add(tool_name)
                grants.append(
                    ToolGrant(
                        grantId=f"grant:{capability_id}:{tool_name}",
                        capabilityId=capability_id,
                        toolName=tool_name,
                        route="local" if "." in tool_name else "task_graph",
                        scope="run",
                        sideEffectPolicy=side_effect,
                        idempotent=idempotent,
                        reasonCodes=(f"capability:{capability_id}",),
                    )
                )

        if not ordered_caps:
            reasons.append("no_requested_capabilities")

        return AuthorizationDecision(
            decisionId=f"authz-{uuid4().hex[:16]}",
            grants=tuple(grants),
            deniedCapabilityIds=tuple(denied),
            reasonCodes=tuple(dict.fromkeys(reasons)),
        )

    def allowed_tool_names(self, decision: AuthorizationDecision | dict[str, Any] | None) -> set[str]:
        if isinstance(decision, AuthorizationDecision):
            return {grant.toolName for grant in decision.grants}
        if not isinstance(decision, dict):
            return set()
        grants = decision.get("grants") or []
        names: set[str] = set()
        for grant in grants:
            if isinstance(grant, dict):
                name = str(grant.get("toolName") or "").strip()
                if name:
                    names.add(name)
        return names

    def effective_tool_names(
        self,
        decision: AuthorizationDecision | dict[str, object] | None,
        *,
        manifest_tools: set[str] | None,
    ) -> set[str]:
        return self.allowed_tool_names(decision).intersection(manifest_tools or set())

    def tool_names_for_capabilities(
        self,
        capability_ids: list[str] | tuple[str, ...] | set[str] | None,
        *,
        manifest_tools: set[str] | None = None,
    ) -> set[str]:
        names: set[str] = set()
        for capability_id in capability_ids or ():
            mapped = _CAPABILITY_TOOL_MAP.get(str(capability_id).strip()) or ()
            for tool_name, _side_effect, _idempotent in mapped:
                if manifest_tools is None or tool_name in manifest_tools:
                    names.add(tool_name)
        return names
