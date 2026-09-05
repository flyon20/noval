from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import time
import unittest
import uuid

from fastapi import HTTPException
from pydantic import BaseModel, ValidationError

from app import main, security
from app.config import settings
from app.registry import MCP_MANIFEST_META_KEY, ToolDefinition, ToolRegistry


SIGNING_KEY = "mcp-signing-test-key-123456789012345"


def signed_payload(
    payload: dict,
    *,
    permissions: set[str] | None = None,
    timestamp: int | None = None,
    nonce: str | None = None,
) -> dict:
    arguments = dict(payload.get("arguments") or {})
    claims = {
        "name": str(payload.get("name") or ""),
        "arguments": arguments,
        "route": str(payload.get("route") or ""),
        "userId": str(arguments.get("userId") or ""),
        "projectId": str(arguments.get("projectId") or ""),
        "supervisorPermissions": sorted(permissions or set()),
        "timestamp": int(time.time()) if timestamp is None else timestamp,
        "nonce": nonce or uuid.uuid4().hex,
    }
    canonical = json.dumps(claims, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
    claims["signature"] = hmac.new(SIGNING_KEY.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    return {**payload, "arguments": arguments, "envelope": claims}


class HarnessResponse:
    def __init__(self, status_code: int, payload: dict) -> None:
        self.status_code = status_code
        self._payload = payload

    def json(self) -> dict:
        return self._payload


def governed_call(payload: dict) -> HarnessResponse:
    try:
        envelope_data = payload.get("envelope")
        envelope = security.SignedCallEnvelope.model_validate(envelope_data) if envelope_data is not None else None
        permissions, arguments = security.verify_signed_call(
            name=str(payload.get("name") or ""),
            arguments=dict(payload.get("arguments") or {}),
            route=payload.get("route"),
            envelope=envelope,
        )
        result = asyncio.run(main.tool_registry.call(
            name=str(payload.get("name") or ""),
            arguments=arguments,
            backend_client=main.backend_client,
            route=payload.get("route"),
            supervisor_permissions=permissions,
        ))
        return HarnessResponse(200, {"name": payload.get("name"), "result": result})
    except ValidationError as exc:
        return HarnessResponse(422, {"detail": exc.errors(include_context=False)})
    except HTTPException as exc:
        return HarnessResponse(exc.status_code, {"detail": exc.detail})


class McpSecurityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.original_key = settings.mcp_internal_api_key
        self.original_signing_key = settings.mcp_call_signing_key
        self.original_max_age = settings.mcp_call_signature_max_age_seconds
        self.original_nonce_guard = security._NONCE_REPLAY_GUARD
        self.original_backend_client = main.backend_client
        settings.mcp_internal_api_key = "fastmcp-test-internal-key-1234567890"
        settings.mcp_call_signing_key = SIGNING_KEY
        settings.mcp_call_signature_max_age_seconds = 60
        security._NONCE_REPLAY_GUARD = security._BoundedMemoryNonceReplayGuard(max_entries=1000)

    def tearDown(self) -> None:
        settings.mcp_internal_api_key = self.original_key
        settings.mcp_call_signing_key = self.original_signing_key
        settings.mcp_call_signature_max_age_seconds = self.original_max_age
        security._NONCE_REPLAY_GUARD = self.original_nonce_guard
        main.backend_client = self.original_backend_client

    def test_internal_token_is_required_and_rejects_short_configured_secret(self) -> None:
        self.assertFalse(security.internal_service_token_valid(None))
        settings.mcp_internal_api_key = "short-fastmcp-key"
        self.assertFalse(security.internal_service_token_valid("short-fastmcp-key"))

    def test_registration_rejects_incomplete_or_invalid_identity_manifest(self) -> None:
        class Args(BaseModel):
            pass

        async def handler(_args: BaseModel, _client: object) -> dict:
            return {}

        with self.assertRaisesRegex(ValueError, "tool manifest missing fields"):
            ToolRegistry().register(ToolDefinition("custom.unscoped", "unscoped", Args, handler))
        with self.assertRaisesRegex(ValueError, "include userId and projectId"):
            ToolRegistry().register(ToolDefinition(
                name="custom.project_only",
                description="invalid project identity",
                args_model=Args,
                handler=handler,
                routes=("market_scan",),
                side_effect_type="read",
                scope_requirement="project",
                timeout_ms=1000,
                identity_keys=("projectId",),
                secret_input_keys=(),
                secret_output_keys=(),
            ))

    def test_registration_accepts_user_scope_and_rejects_exposed_project_id(self) -> None:
        class UserArgs(BaseModel):
            userId: int

        class InvalidArgs(BaseModel):
            userId: int
            projectId: int | None = None

        async def handler(_args: BaseModel, _client: object) -> dict:
            return {}

        registry = ToolRegistry()
        registry.register(ToolDefinition(
            name="custom.user_scoped",
            description="user scoped",
            args_model=UserArgs,
            handler=handler,
            routes=("market_scan",),
            side_effect_type="read",
            scope_requirement="user",
            timeout_ms=1000,
            identity_keys=("userId",),
            secret_input_keys=(),
            secret_output_keys=(),
        ))
        self.assertEqual(["userId"], registry.list_tools()[0]["identityKeys"])
        with self.assertRaisesRegex(ValueError, "must not expose projectId"):
            ToolRegistry().register(ToolDefinition(
                name="custom.invalid_user_scope",
                description="invalid",
                args_model=InvalidArgs,
                handler=handler,
                routes=("market_scan",),
                side_effect_type="read",
                scope_requirement="user",
                timeout_ms=1000,
                identity_keys=("userId",),
                secret_input_keys=(),
                secret_output_keys=(),
            ))

    def test_standard_listing_exposes_complete_governed_manifest_in_meta(self) -> None:
        manifests = {tool["name"]: tool for tool in main.tool_registry.list_standard_tools()}
        rank = manifests["rank.lookup"]
        manifest = rank["_meta"][MCP_MANIFEST_META_KEY]
        self.assertEqual(["market_scan", "mixed_creation_research"], manifest["routes"])
        self.assertEqual("read", manifest["side_effect_type"])
        self.assertEqual("user", manifest["scope_requirement"])
        self.assertEqual(["userId"], manifest["identity_keys"])
        self.assertNotIn("userId", rank["inputSchema"]["properties"])
        project = manifests["project.retrieve"]
        self.assertNotIn("projectId", project["inputSchema"]["properties"])
        self.assertIn("workId", project["inputSchema"]["properties"])
        self.assertIn("memory.admin.list", manifests)

    def test_unknown_tool_route_identity_and_project_scope_fail_closed(self) -> None:
        unknown = governed_call(signed_payload({
            "name": "shell.exec",
            "route": "market_scan",
            "arguments": {"userId": 7},
        }))
        missing_route = signed_payload({
            "name": "rank.lookup",
            "route": "market_scan",
            "arguments": {"platform": "fanqie", "userId": 7},
        })
        missing_route.pop("route")
        missing_user = governed_call(signed_payload({
            "name": "rank.lookup",
            "route": "market_scan",
            "arguments": {"platform": "fanqie"},
        }))
        missing_project = governed_call(signed_payload({
            "name": "project.retrieve",
            "route": "project_creation",
            "arguments": {"userId": 7, "workId": 11, "query": "continuity"},
        }))
        self.assertEqual(404, unknown.status_code)
        self.assertEqual(403, governed_call(missing_route).status_code)
        self.assertEqual(422, missing_user.status_code)
        self.assertEqual(403, missing_project.status_code)

    def test_write_and_admin_tools_require_explicit_permissions(self) -> None:
        missing_idempotency = governed_call(signed_payload({
            "name": "rank.refresh",
            "route": "market_scan",
            "arguments": {"platform": "fanqie", "forceReason": "refresh stale board", "userId": 7},
        }, permissions={"rank.refresh"}))
        admin_payload = {"name": "memory.admin.list", "route": "admin", "arguments": {"userId": 7, "projectId": 9}}
        denied = governed_call(signed_payload(admin_payload))
        allowed = governed_call(signed_payload(admin_payload, permissions={"memory.admin.list"}))
        wrong_route = governed_call(signed_payload({**admin_payload, "route": "mixed_creation_research"}, permissions={"admin:*"}))
        self.assertEqual("idempotency key required", missing_idempotency.json()["detail"])
        self.assertEqual(403, denied.status_code)
        self.assertEqual(200, allowed.status_code)
        self.assertEqual(403, wrong_route.status_code)

    def test_risky_tool_arguments_are_rejected_but_natural_language_is_allowed(self) -> None:
        for arguments in (
            {"url": "https://evil.example/steal"},
            {"filePath": "C:/Users/test/.env"},
            {"query": "select * from users"},
            {"query": "powershell Get-ChildItem Env:"},
            {"sql": "harmless text"},
        ):
            response = governed_call(signed_payload({
                "name": "rank.lookup",
                "route": "market_scan",
                "arguments": {"platform": "fanqie", "userId": 7, **arguments},
            }))
            self.assertEqual(400, response.status_code)
        for query in (
            "Select books from the latest ranking",
            "Update my understanding of the latest ranking",
            "Delete repetitive wording from this chapter",
            "Create a story from the ranking data",
        ):
            security.validate_safe_arguments({"query": query})

    def test_real_sql_structures_are_rejected(self) -> None:
        for query in (
            "select * from users where project_id = 9",
            "SELECT title, rank_no FROM rankings WHERE platform = 'fanqie';",
            "select title from rankings -- latest",
            "/* ranked */ SELECT title FROM rankings",
            "1; DROP TABLE books;",
            "DELETE FROM books WHERE id = 1",
        ):
            with self.subTest(query=query), self.assertRaises(HTTPException) as raised:
                security.validate_safe_arguments({"query": query})
            self.assertEqual(400, raised.exception.status_code)

    def test_chinese_vector_query_is_allowed_and_sql_query_is_rejected(self) -> None:
        class FakeBackendClient:
            async def post(self, path: str, payload: dict) -> dict:
                return {"path": path, "payload": payload}

        main.backend_client = FakeBackendClient()
        accepted = governed_call(signed_payload({
            "name": "knowledge.vector_search",
            "route": "mixed_creation_research",
            "arguments": {"query": "分析这部小说的叙事节奏与人物成长", "userId": 7},
        }))
        rejected = governed_call(signed_payload({
            "name": "knowledge.vector_search",
            "route": "mixed_creation_research",
            "arguments": {"query": "select * from users where project_id = 9", "userId": 7},
        }))
        self.assertEqual(200, accepted.status_code)
        self.assertEqual(7, accepted.json()["result"]["payload"]["userId"])
        self.assertEqual("unsafe tool argument: query", rejected.json()["detail"])

    def test_missing_forged_and_noncanonical_envelopes_are_rejected(self) -> None:
        missing = governed_call({
            "name": "rank.lookup",
            "route": "market_scan",
            "arguments": {"platform": "fanqie", "userId": 7},
        })
        original = {
            "name": "rank.lookup",
            "route": "market_scan",
            "arguments": {"platform": "fanqie", "userId": 7},
        }
        forged_route = signed_payload(original)
        forged_route["route"] = "mixed_creation_research"
        forged_identity = signed_payload(original)
        forged_identity["arguments"]["userId"] = 8
        noncanonical = signed_payload({
            **original,
            "arguments": {"platform": "fanqie", "limit": 1, "userId": 7},
        })
        noncanonical["envelope"] = json.loads(json.dumps(noncanonical["envelope"]))
        noncanonical["arguments"]["limit"] = 1.0
        self.assertEqual(401, missing.status_code)
        self.assertEqual(403, governed_call(forged_route).status_code)
        self.assertEqual(403, governed_call(forged_identity).status_code)
        self.assertEqual(403, governed_call(noncanonical).status_code)

    def test_placeholder_expired_and_invalid_hmac_fail_closed(self) -> None:
        payload = {
            "name": "reader.simulate_feedback",
            "route": "mixed_creation_research",
            "arguments": {"question": "check hook", "userId": 7},
        }
        expired = governed_call(signed_payload(payload, timestamp=int(time.time()) - 61))
        invalid = signed_payload(payload)
        invalid["envelope"]["signature"] = "0" * 64
        invalid_response = governed_call(invalid)
        settings.mcp_call_signing_key = "CHANGE_ME_WITH_A_RANDOM_MCP_CALL_SIGNING_KEY"
        placeholder = governed_call(signed_payload(payload))
        self.assertEqual("expired MCP call signature", expired.json()["detail"])
        self.assertEqual("invalid MCP call signature", invalid_response.json()["detail"])
        self.assertEqual(503, placeholder.status_code)

    def test_nonce_replay_is_rejected(self) -> None:
        payload = signed_payload({
            "name": "reader.simulate_feedback",
            "route": "mixed_creation_research",
            "arguments": {"question": "check hook", "userId": 7},
        })
        self.assertEqual(200, governed_call(payload).status_code)
        replay = governed_call(payload)
        self.assertEqual(401, replay.status_code)
        self.assertEqual("replayed MCP call nonce", replay.json()["detail"])

    def test_redis_nonce_guard_is_shared_and_capacity_bounded(self) -> None:
        class FakeRedis:
            def __init__(self) -> None:
                self.nonces: set[str] = set()
                self.count = 0

            def eval(self, _script: str, _keys: int, nonce_key: str, _counter_key: str, _ttl: int, max_entries: int) -> int:
                if nonce_key in self.nonces:
                    return 0
                if self.count >= int(max_entries):
                    return -1
                self.nonces.add(nonce_key)
                self.count += 1
                return 1

        original_host = settings.redis_host
        original_max_entries = settings.mcp_nonce_max_entries
        settings.redis_host = "redis"
        settings.mcp_nonce_max_entries = 1
        shared = FakeRedis()
        first_guard = security._RedisNonceReplayGuard()
        second_guard = security._RedisNonceReplayGuard()
        first_guard._client = shared
        second_guard._client = shared
        try:
            first_guard.consume("a" * 16, now=100, expires_at=160)
            with self.assertRaises(HTTPException) as replay:
                second_guard.consume("a" * 16, now=100, expires_at=160)
            with self.assertRaises(HTTPException) as exhausted:
                second_guard.consume("b" * 16, now=100, expires_at=160)
            self.assertEqual(401, replay.exception.status_code)
            self.assertEqual(503, exhausted.exception.status_code)
        finally:
            settings.redis_host = original_host
            settings.mcp_nonce_max_entries = original_max_entries

    def test_valid_signed_call_is_accepted_and_missing_key_fails_closed(self) -> None:
        payload = signed_payload({
            "name": "reader.simulate_feedback",
            "route": "mixed_creation_research",
            "arguments": {"question": "check hook", "userId": 7},
        })
        self.assertEqual(200, governed_call(payload).status_code)
        settings.mcp_call_signing_key = ""
        self.assertEqual(503, governed_call(signed_payload({
            "name": "rank.lookup",
            "route": "market_scan",
            "arguments": {"platform": "fanqie", "userId": 7},
        })).status_code)


if __name__ == "__main__":
    unittest.main()
