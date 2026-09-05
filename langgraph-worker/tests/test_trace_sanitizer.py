from __future__ import annotations

import json

from app.services.harness.trace_sanitizer import sanitize_trace_for_persistence


def test_sanitizer_removes_body_tool_payloads_uploads_and_credentials() -> None:
    chapter_body = "PRIVATE_CHAPTER_BODY_" + "x" * 320
    upload_body = "PRIVATE_UPLOAD_MARKER_" + "y" * 320
    credential = "sk-private-trace-secret"
    payload = {
        "answer": chapter_body,
        "answerDeltas": [chapter_body],
        "toolRuns": [
            {
                "name": "project.retrieve",
                "status": "succeeded",
                "durationMs": 18,
                "input": {"query": chapter_body, "apiKey": credential},
                "output": {
                    "evidence": [
                        {
                            "chapterNo": 12,
                            "generationId": 77,
                            "chunkText": chapter_body,
                            "contentHash": "a" * 64,
                        }
                    ],
                    "uploadedFile": {"name": "private.md", "content": upload_body},
                },
            }
        ],
        "projectKnowledge": {
            "retrievedEvidence": [
                {
                    "chapterNo": 12,
                    "generationId": 77,
                    "chunkText": chapter_body,
                    "contentHash": "a" * 64,
                }
            ]
        },
        "retrievalDiagnostics": {
            "partialFlush": True,
            "vectorLatencyMs": 18,
            "generationId": 77,
            "degradationReasons": ["vector_unavailable"],
        },
        "trace": {"summary": "s" * 260, "accessToken": credential},
    }

    sanitized = sanitize_trace_for_persistence(payload)
    serialized = json.dumps(sanitized, ensure_ascii=False)

    assert chapter_body not in serialized
    assert upload_body not in serialized
    assert credential not in serialized
    assert sanitized["toolRuns"] == [
        {
            "name": "project.retrieve",
            "status": "succeeded",
            "durationMs": 18,
            "inputHash": sanitize_trace_for_persistence({"input": payload["toolRuns"][0]["input"]})["inputHash"],
            "outputHash": sanitize_trace_for_persistence({"output": payload["toolRuns"][0]["output"]})["outputHash"],
        }
    ]
    evidence = sanitized["projectKnowledge"]["retrievedEvidence"][0]
    assert evidence == {
        "chapterNo": 12,
        "generationId": 77,
        "contentHash": "a" * 64,
    }
    assert sanitized["retrievalDiagnostics"] == {
        "partialFlush": True,
        "vectorLatencyMs": 18,
        "generationId": 77,
        "degradationReasons": ["vector_unavailable"],
    }
    assert len(sanitized["trace"]["summary"]) == 200


def test_sanitizer_preserves_observability_contract_fields() -> None:
    payload = {
        "trace": {
            "answerMode": "mixed_creation",
            "answerStatus": "creative_answer",
            "answerBoundary": "creative_inference",
            "executionPath": "DIRECT",
            "promptPolicy": "evidence_first_fact_grounding",
            "runtimeConfig": {
                "source": "backend",
                "specialistMcpRequested": True,
                "specialistMcpEffective": False,
                "specialistMcpDeniedReason": "execution_path_not_delegated",
                "maxEvidenceItems": None,
            },
            "diagnostics": {
                "retrieval": {
                    "inputCount": 2,
                    "selectedCount": 1,
                    "inputSourceTypeCounts": {"CHAPTER": 2},
                    "selectedSourceTypeCounts": {"CHAPTER": 1},
                }
            },
            "toolRuns": [
                {
                    "name": "rank.lookup",
                    "status": "succeeded",
                    "plane": "system_internal",
                    "input": {"taskType": "market_scan", "query": "secret body"},
                }
            ],
            "memoryCandidates": [
                {
                    "scope": "project",
                    "type": "constraint",
                    "factKey": "project.constraint.abc",
                    "confidence": 0.82,
                    "content": "should not leak",
                    "reason": "explicit writing constraint",
                    "sourceTraceId": "trace-1",
                }
            ],
        }
    }

    sanitized = sanitize_trace_for_persistence(payload)
    trace = sanitized["trace"]
    assert trace["answerMode"] == "mixed_creation"
    assert trace["executionPath"] == "DIRECT"
    assert trace["promptPolicy"] == "evidence_first_fact_grounding"
    assert trace["runtimeConfig"]["maxEvidenceItems"] is None
    assert trace["runtimeConfig"]["specialistMcpRequested"] is True
    assert trace["diagnostics"]["retrieval"]["inputCount"] == 2
    assert trace["diagnostics"]["retrieval"]["inputSourceTypeCounts"] == {"CHAPTER": 2}
    assert "input" not in trace["toolRuns"][0]
    assert "inputHash" in trace["toolRuns"][0]
    assert trace["toolRuns"][0]["plane"] == "system_internal"
    assert "content" not in trace["memoryCandidates"][0]
    assert trace["memoryCandidates"][0]["factKey"] == "project.constraint.abc"


def test_sanitizer_is_idempotent_for_tool_run_hashes() -> None:
    first = sanitize_trace_for_persistence(
        {
            "trace": {
                "toolRuns": [
                    {
                        "name": "rank.lookup",
                        "status": "succeeded",
                        "input": {"taskType": "market_scan"},
                        "output": {"total": 1},
                    }
                ]
            }
        }
    )
    second = sanitize_trace_for_persistence(first)
    assert second["trace"]["toolRuns"][0]["inputHash"] == first["trace"]["toolRuns"][0]["inputHash"]
    assert second["trace"]["toolRuns"][0]["outputHash"] == first["trace"]["toolRuns"][0]["outputHash"]
    assert "input" not in second["trace"]["toolRuns"][0]
    assert "output" not in second["trace"]["toolRuns"][0]


def test_sanitizer_bounds_harness_trace_and_removes_semantic_or_skill_bodies() -> None:
    private_goal = "PRIVATE_USER_GOAL_MARKER"
    private_skill = "PRIVATE_SKILL_INSTRUCTIONS_MARKER"
    payload = {
        "trace": {
            "intentEnvelope": {
                "envelopeId": "intent-1",
                "fingerprint": "sha256:intent",
                "goal": private_goal,
                "reasonCodes": ["rule:market"],
            },
            "capabilityPlan": {
                "planId": "plan-1",
                "fingerprint": "sha256:plan",
                "capabilityIds": ["market.read"],
                "skillInstructions": private_skill,
                "apiKey": "sk-private-harness-secret",
                "reasonCodes": ["market_retrieval"],
            },
            "evidence": [
                {
                    "evidenceId": f"rank:{index}",
                    "content": f"private evidence body {index}",
                }
                for index in range(75)
            ],
        }
    }

    sanitized = sanitize_trace_for_persistence(payload)
    serialized = json.dumps(sanitized, ensure_ascii=False)
    trace = sanitized["trace"]

    assert private_goal not in serialized
    assert private_skill not in serialized
    assert "sk-private-harness-secret" not in serialized
    assert len(trace["evidence"]) == 50
    assert trace["intentEnvelope"]["envelopeId"] == "intent-1"
    assert trace["capabilityPlan"]["capabilityIds"] == ["market.read"]
    assert all("content" not in item for item in trace["evidence"])


def test_sanitizer_keeps_bounded_authorization_boundary_without_schema_or_secrets() -> None:
    payload = {
        "trace": {
            "authorizationBoundary": {
                "version": "authorization-boundary-v1",
                "fingerprint": "sha256:boundary",
                "planGrantToolNames": ["rank.lookup"],
                "localEffectiveToolNames": ["rank.lookup"],
                "providerVisibleToolNames": [],
                "reasonCodes": ["specialist_mcp:config_disabled"],
                "inputSchema": {"type": "object", "required": ["private"]},
                "apiKey": "sk-private-boundary-secret",
            }
        }
    }

    sanitized = sanitize_trace_for_persistence(payload)
    boundary = sanitized["trace"]["authorizationBoundary"]
    serialized = json.dumps(boundary, ensure_ascii=False)

    assert boundary["fingerprint"] == "sha256:boundary"
    assert boundary["localEffectiveToolNames"] == ["rank.lookup"]
    assert "inputSchema" not in boundary
    assert "sk-private-boundary-secret" not in serialized
