from app.services.harness.execution_path import ExecutionPath, ExecutionPathRouter


def test_routes_creative_request_directly() -> None:
    decision = ExecutionPathRouter().decide(
        intent="creative_advice",
        domain_intent="outline_building",
        task_graph={"tasks": [{"type": "outline_building", "tools": []}]},
        tool_plan=[],
    )

    assert decision.path is ExecutionPath.DIRECT


def test_routes_project_knowledge_request_through_retrieval() -> None:
    decision = ExecutionPathRouter().decide(
        intent="project_creation",
        domain_intent="project_creation",
        task_graph={"tasks": [{"type": "outline_building", "tools": ["memory.project_context"]}]},
        tool_plan=[{"name": "memory.project_context"}],
    )

    assert decision.path is ExecutionPath.RETRIEVE


def test_routes_mixed_multi_task_request_as_complex() -> None:
    decision = ExecutionPathRouter().decide(
        intent="mixed_creation_research",
        domain_intent="mixed_creation_research",
        task_graph={
            "tasks": [
                {"type": "market_scan", "tools": ["rank.lookup"]},
                {"type": "outline_building", "tools": ["skill.lookup"]},
            ]
        },
        tool_plan=[],
    )

    assert decision.path is ExecutionPath.COMPLEX
    assert decision.as_trace()["reason"] == "multi_task_or_mixed_intent"


def test_legacy_book_research_route_overrides_domain_classifier_miss() -> None:
    decision = ExecutionPathRouter().decide(
        intent="single_book_research",
        domain_intent="out_of_scope",
        task_graph={"tasks": [{"type": "followup_context", "tools": ["memory.project_context"]}]},
        tool_plan=[{"name": "book_research_pack"}],
        has_project_context=False,
    )

    assert decision.path is ExecutionPath.RETRIEVE
