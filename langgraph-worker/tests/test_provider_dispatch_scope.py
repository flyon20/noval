from __future__ import annotations

import asyncio
import unittest
from typing import Any

from app.services.harness.provider_dispatch_scope import (
    ProviderCapabilities,
    ProviderDispatch,
    current_provider_dispatch,
    current_provider_dispatch_scope,
    provider_dispatch_scope,
    resolve_provider_dispatch,
    resolve_provider_dispatch_for_model,
)
from app.services.harness.webnovel_harness import WebnovelHarness
from app.models.knowledge import KnowledgeChatRequest
from app.services.knowledge_client import KnowledgeBackendClient


def dispatch_for(
    profile_key: str,
    profile_version: str,
    *,
    api_key: str,
) -> ProviderDispatch:
    return ProviderDispatch(
        profile_key=profile_key,
        profile_version=profile_version,
        endpoint=f"https://{profile_key}.example/v1",
        model=f"{profile_key}-model",
        provider_type="openai-compatible",
        protocol="responses",
        api_key=api_key,
    )


def provider_capabilities(**overrides: Any) -> dict[str, Any]:
    values: dict[str, Any] = {
        "schemaVersion": 1,
        "supportsStreaming": True,
        "supportsTools": True,
        "supportsJsonObject": True,
        "supportsReasoning": True,
        "reportsUsage": True,
        "reportsCacheUsage": True,
    }
    values.update(overrides)
    return values


class ProviderCapabilitiesContractTest(unittest.TestCase):
    def test_round_trips_responses_prompt_cache_contract(self) -> None:
        payload = provider_capabilities(promptCache={
            "strategy": "openai_gpt_5_6",
            "mode": "implicit",
            "retention": "30m",
            "breakpoint": "stable_prefix",
        })

        capabilities = ProviderCapabilities.from_payload(payload)

        self.assertIsNotNone(capabilities)
        assert capabilities is not None
        self.assertEqual("openai_gpt_5_6", capabilities.prompt_cache.strategy)
        self.assertEqual(payload, capabilities.snapshot())
        self.assertIn(capabilities.prompt_cache.signature, capabilities.signature)

    def test_rejects_incompatible_prompt_cache_contract(self) -> None:
        with self.assertRaisesRegex(ValueError, "openai_legacy"):
            ProviderCapabilities.from_payload(provider_capabilities(promptCache={
                "strategy": "openai_legacy",
                "mode": "explicit",
                "retention": "24h",
                "breakpoint": "stable_prefix",
            }))

    def test_rejects_prompt_cache_contract_on_chat_profile(self) -> None:
        with self.assertRaisesRegex(ValueError, "Responses protocol"):
            ProviderDispatch(
                profile_key="chat-profile",
                profile_version="v1",
                endpoint="https://chat.example/v1",
                model="gpt-5.6-sol",
                provider_type="openai-compatible",
                protocol="chat_completions",
                api_key="test-only",
                provider_capabilities=provider_capabilities(promptCache={
                    "strategy": "openai_gpt_5_6",
                    "mode": "implicit",
                    "retention": "30m",
                    "breakpoint": "stable_prefix",
                }),
            )


class FakeResponse:
    def __init__(self, payload: dict[str, Any]) -> None:
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict[str, Any]:
        return self._payload


class FakeAsyncClient:
    def __init__(self, response_payload: dict[str, Any]) -> None:
        self.response_payload = response_payload
        self.posts: list[dict[str, Any]] = []

    async def post(self, path: str, *, json: dict, headers: dict) -> FakeResponse:
        self.posts.append({"path": path, "json": json, "headers": headers})
        return FakeResponse(self.response_payload)

    async def aclose(self) -> None:
        return None


class ProviderDispatchTransportTest(unittest.IsolatedAsyncioTestCase):
    async def test_resolves_dispatch_over_internal_token_transport_without_repr_projection(self) -> None:
        secret = "provider-secret-never-project"
        fake_http = FakeAsyncClient({
            "data": {
                "profileKey": "gateway-a",
                "profileVersion": "version-a",
                "endpoint": "https://gateway-a.example/v1",
                "model": "gateway-model",
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": provider_capabilities(),
                "apiKey": secret,
            },
        })
        client = KnowledgeBackendClient(
            base_url="http://127.0.0.1:8080",
            internal_api_key="worker-internal-token",
            async_client_factory=lambda **_kwargs: fake_http,
        )

        dispatch = await client.resolve_provider_dispatch("gateway-a", "version-a")

        self.assertEqual(
            "/internal/knowledge/agent/provider-dispatch/resolve",
            fake_http.posts[0]["path"],
        )
        self.assertEqual(
            {"profileKey": "gateway-a", "profileVersion": "version-a"},
            fake_http.posts[0]["json"],
        )
        self.assertEqual(
            "worker-internal-token",
            fake_http.posts[0]["headers"]["X-Internal-Service-Token"],
        )
        self.assertEqual(secret, dispatch.api_key)
        self.assertTrue(dispatch.route_snapshot()["providerCapabilities"]["supportsTools"])
        self.assertNotIn(secret, repr(dispatch))
        self.assertNotIn("apiKey", dispatch.route_snapshot())

    async def test_rejects_backend_identity_mismatch(self) -> None:
        fake_http = FakeAsyncClient({
            "profileKey": "gateway-b",
            "profileVersion": "version-a",
            "endpoint": "https://gateway-b.example/v1",
            "model": "gateway-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "apiKey": "mismatched-secret",
        })
        client = KnowledgeBackendClient(
            base_url="http://127.0.0.1:8080",
            internal_api_key="worker-internal-token",
            async_client_factory=lambda **_kwargs: fake_http,
        )

        with self.assertRaisesRegex(ValueError, "identity mismatch"):
            await client.resolve_provider_dispatch("gateway-a", "version-a")


class ProviderDispatchScopeTest(unittest.IsolatedAsyncioTestCase):
    def test_accepts_backend_runtime_circuit_state_object_shape(self) -> None:
        policy = {
            "schemaVersion": 1,
            "enabled": True,
            "orderedProfileKeys": ["primary", "backup"],
            "maxFailovers": 1,
            "cooldownSeconds": 90,
            "circuitStates": {
                "primary": {
                    "profileKey": "primary",
                    "profileVersion": "v1",
                    "state": "CLOSED",
                    "failureCount": 0,
                },
                "backup": {
                    "profileKey": "backup",
                    "profileVersion": "v1",
                    "state": "OPEN",
                    "failureCount": 1,
                },
            },
        }

        from app.services.harness.provider_dispatch_scope import ProviderRoutingPolicy

        parsed = ProviderRoutingPolicy.from_payload(policy)

        self.assertTrue(parsed.active)
        self.assertEqual("closed", parsed.circuit_state("primary"))
        self.assertEqual("open", parsed.circuit_state("backup"))
        self.assertFalse(ProviderRoutingPolicy.from_payload({
            **policy,
            "maxFailovers": 0,
        }).active)

    async def test_explicit_routing_policy_preserves_order_and_switches_once_lazily(self) -> None:
        capabilities = provider_capabilities()
        routes = [
            {
                "profileKey": key,
                "profileVersion": "v1",
                "endpoint": f"https://{key}.example/v1",
                "model": f"{key}-model",
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": capabilities,
                "isDefault": key == "primary",
            }
            for key in ("primary", "backup", "unused")
        ]
        calls: list[str] = []

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            calls.append(profile_key)
            route = next(item for item in routes if item["profileKey"] == profile_key)
            return ProviderDispatch(
                profile_key=profile_key,
                profile_version=profile_version,
                endpoint=route["endpoint"],
                model=route["model"],
                provider_type=route["providerType"],
                protocol=route["protocol"],
                api_key=f"{profile_key}-secret",
                provider_capabilities=capabilities,
            )

        policy = {
            "schemaVersion": 1,
            "enabled": True,
            "orderedProfileKeys": ["primary", "backup", "unused"],
            "maxFailovers": 1,
            "circuitStates": {},
        }
        async with provider_dispatch_scope(
            resolver,
            routes=routes,
            preferred_model="primary-model",
            routing_policy=policy,
        ) as scope:
            primary = await scope.resolve_for_model("primary-model")
            self.assertEqual("primary", primary.profile_key)
            self.assertEqual(["primary"], calls)

            backup = await scope.claim_failover(*primary.identity)
            self.assertIsNotNone(backup)
            assert backup is not None
            self.assertEqual("backup", backup.profile_key)
            self.assertEqual(["primary", "backup"], calls)
            self.assertIs(backup, await scope.resolve_for_model("primary-model"))
            self.assertIsNone(await scope.claim_failover(*backup.identity))
            self.assertEqual(["primary", "backup"], calls)

    async def test_failed_backup_resolution_consumes_the_only_switch_without_trying_third_route(self) -> None:
        capabilities = provider_capabilities()
        routes = [
            {
                "profileKey": key,
                "profileVersion": "v1",
                "endpoint": f"https://{key}.example/v1",
                "model": f"{key}-model",
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": capabilities,
                "isDefault": key == "primary",
            }
            for key in ("primary", "backup", "third")
        ]
        calls: list[str] = []

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            calls.append(profile_key)
            if profile_key == "backup":
                raise RuntimeError("backup credential unavailable")
            route = next(item for item in routes if item["profileKey"] == profile_key)
            return ProviderDispatch(
                profile_key=profile_key,
                profile_version=profile_version,
                endpoint=route["endpoint"],
                model=route["model"],
                provider_type=route["providerType"],
                protocol=route["protocol"],
                api_key=f"{profile_key}-secret",
                provider_capabilities=capabilities,
            )

        async with provider_dispatch_scope(
            resolver,
            routes=routes,
            preferred_model="primary-model",
            routing_policy={
                "schemaVersion": 1,
                "enabled": True,
                "orderedProfileKeys": ["primary", "backup", "third"],
                "maxFailovers": 1,
                "circuitStates": {},
            },
        ) as scope:
            primary = await scope.resolve_for_model("primary-model")
            with self.assertRaisesRegex(RuntimeError, "backup credential unavailable"):
                await scope.claim_failover(*primary.identity)
            self.assertIsNone(await scope.claim_failover(*primary.identity))

        self.assertEqual(["primary", "backup"], calls)

    async def test_routing_policy_rejects_implicit_or_incompatible_candidates(self) -> None:
        capabilities = provider_capabilities()
        routes = [
            {
                "profileKey": "primary",
                "profileVersion": "v1",
                "endpoint": "https://primary.example/v1",
                "model": "primary-model",
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": capabilities,
                "isDefault": True,
            },
            {
                "profileKey": "backup",
                "profileVersion": "v1",
                "endpoint": "https://backup.example/v1",
                "model": "backup-model",
                "providerType": "openai-compatible",
                "protocol": "chat_completions",
                "providerCapabilities": capabilities,
            },
        ]

        async def resolver(_key: str, _version: str) -> ProviderDispatch:
            raise AssertionError("invalid routing policy must fail before credential resolution")

        with self.assertRaisesRegex(ValueError, "compatible"):
            async with provider_dispatch_scope(
                resolver,
                routes=routes,
                preferred_model="primary-model",
                routing_policy={
                    "schemaVersion": 1,
                    "enabled": True,
                    "orderedProfileKeys": ["primary", "backup"],
                    "maxFailovers": 1,
                    "circuitStates": {},
                },
            ):
                self.fail("incompatible routing policy must not become active")

    async def test_declared_capability_mismatch_excludes_non_primary_route(self) -> None:
        calls: list[tuple[str, str]] = []

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            calls.append((profile_key, profile_version))
            return dispatch_for(profile_key, profile_version, api_key="must-not-resolve")

        routes = [{
            "profileKey": "primary",
            "profileVersion": "v1",
            "endpoint": "https://gateway.example/v1",
            "model": "deep-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "providerCapabilities": provider_capabilities(),
            "isDefault": True,
        }, {
            "profileKey": "no-tools",
            "profileVersion": "v1",
            "endpoint": "https://gateway.example/v1",
            "model": "intent-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "providerCapabilities": provider_capabilities(supportsTools=False),
        }]

        async with provider_dispatch_scope(resolver, routes=routes, preferred_model="deep-model"):
            with self.assertRaisesRegex(ValueError, "outside the frozen catalog"):
                await resolve_provider_dispatch("no-tools", "v1")

        self.assertEqual([], calls)

    async def test_declared_capability_requires_resolver_payload_parity(self) -> None:
        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            return dispatch_for(profile_key, profile_version, api_key="resolver-secret")

        routes = [{
            "profileKey": "primary",
            "profileVersion": "v1",
            "endpoint": "https://primary.example/v1",
            "model": "primary-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "providerCapabilities": provider_capabilities(),
            "isDefault": True,
        }]

        async with provider_dispatch_scope(resolver, routes=routes, preferred_model="primary-model"):
            with self.assertRaisesRegex(ValueError, "does not match the frozen catalog"):
                await resolve_provider_dispatch("primary", "v1")

    async def test_legacy_unknown_catalog_keeps_only_primary_route(self) -> None:
        calls: list[tuple[str, str]] = []

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            calls.append((profile_key, profile_version))
            return dispatch_for(profile_key, profile_version, api_key="must-not-resolve")

        routes = [{
            "profileKey": "primary",
            "profileVersion": "v1",
            "endpoint": "https://gateway.example/v1",
            "model": "primary-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "isDefault": True,
        }, {
            "profileKey": "secondary",
            "profileVersion": "v1",
            "endpoint": "https://gateway.example/v1",
            "model": "secondary-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
        }]

        async with provider_dispatch_scope(resolver, routes=routes, preferred_model="primary-model"):
            with self.assertRaisesRegex(ValueError, "outside the frozen catalog"):
                await resolve_provider_dispatch("secondary", "v1")

        self.assertEqual([], calls)

    async def test_harness_lifecycle_rejects_new_run_when_runtime_config_failed(self) -> None:
        class FakeKnowledgeClient:
            def __init__(self) -> None:
                self.resolve_calls = 0

            async def resolve_provider_dispatch(self, *_args: str) -> ProviderDispatch:
                self.resolve_calls += 1
                raise AssertionError("failed runtime config must stop before credential resolution")

        class FakeRuntime:
            def __init__(self) -> None:
                self.knowledge_client = FakeKnowledgeClient()

            async def _load_agent_governance(self) -> dict[str, Any]:
                return {
                    "source": "backend",
                    "config": {},
                    "experts": [{"expertName": "market-analysis"}],
                    "errors": {"config": "TimeoutError"},
                    "error": "config:TimeoutError",
                }

            @staticmethod
            def _runtime_config_for_state(
                governance: dict[str, Any],
                config: dict[str, Any],
            ) -> dict[str, Any]:
                return {"source": governance.get("source"), **config}

            @staticmethod
            def _model_name(_request: KnowledgeChatRequest) -> str:
                return "legacy-model"

        runtime = FakeRuntime()
        harness = WebnovelHarness(runtime)  # type: ignore[arg-type]

        with self.assertRaisesRegex(RuntimeError, "agent runtime config is unavailable"):
            async with harness._provider_dispatch_lifecycle(
                KnowledgeChatRequest(question="test"),
                None,
            ):
                self.fail("failed runtime config must not fall back to the global route")

        self.assertEqual(0, runtime.knowledge_client.resolve_calls)
        self.assertIsNone(current_provider_dispatch_scope())

    async def test_harness_lifecycle_allows_successful_empty_provider_catalog(self) -> None:
        class FakeRuntime:
            knowledge_client = object()

            async def _load_agent_governance(self) -> dict[str, Any]:
                return {
                    "source": "backend",
                    "config": {"providerProfiles": []},
                    "experts": [],
                    "runtimeSkills": [],
                }

            @staticmethod
            def _runtime_config_for_state(
                governance: dict[str, Any],
                config: dict[str, Any],
            ) -> dict[str, Any]:
                return {"source": governance.get("source"), **config}

            @staticmethod
            def _model_name(_request: KnowledgeChatRequest) -> str:
                return "legacy-model"

        harness = WebnovelHarness(FakeRuntime())  # type: ignore[arg-type]
        async with harness._provider_dispatch_lifecycle(
            KnowledgeChatRequest(question="test"),
            None,
        ) as governance:
            self.assertEqual("backend", governance["source"] if governance else None)
            self.assertIsNone(current_provider_dispatch_scope())

    async def test_harness_lifecycle_rejects_declared_profile_without_resolver(self) -> None:
        routes = [{
            "profileKey": "gateway",
            "profileVersion": "v1",
            "endpoint": "https://gateway.example/v1",
            "model": "deep-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "isDefault": True,
        }]

        class FakeRuntime:
            knowledge_client = object()

            async def _load_agent_governance(self) -> dict[str, Any]:
                return {"source": "backend", "config": {"providerProfiles": routes}}

            @staticmethod
            def _runtime_config_for_state(
                governance: dict[str, Any],
                config: dict[str, Any],
            ) -> dict[str, Any]:
                return {"source": governance.get("source"), **config}

            @staticmethod
            def _model_name(_request: KnowledgeChatRequest) -> str:
                return "deep-model"

        harness = WebnovelHarness(FakeRuntime())  # type: ignore[arg-type]
        with self.assertRaisesRegex(RuntimeError, "provider dispatch resolver is unavailable"):
            async with harness._provider_dispatch_lifecycle(
                KnowledgeChatRequest(question="test"),
                None,
            ):
                self.fail("declared Provider Profile must not fall back to the global route")
        self.assertIsNone(current_provider_dispatch_scope())

    async def test_harness_lifecycle_ignores_legacy_unspecified_profiles(self) -> None:
        routes = [{
            "profileKey": "legacy",
            "profileVersion": "v1",
            "endpoint": "https://legacy.example/v1",
            "model": "legacy-model",
            "providerType": "openai-compatible",
            "protocol": "unspecified",
            "isDefault": True,
        }]

        class FakeRuntime:
            knowledge_client = object()

            async def _load_agent_governance(self) -> dict[str, Any]:
                return {"source": "backend", "config": {"providerProfiles": routes}}

            @staticmethod
            def _runtime_config_for_state(
                governance: dict[str, Any],
                config: dict[str, Any],
            ) -> dict[str, Any]:
                return {"source": governance.get("source"), **config}

            @staticmethod
            def _model_name(_request: KnowledgeChatRequest) -> str:
                return "legacy-model"

        harness = WebnovelHarness(FakeRuntime())  # type: ignore[arg-type]
        async with harness._provider_dispatch_lifecycle(
            KnowledgeChatRequest(question="test"),
            None,
        ) as governance:
            self.assertEqual("backend", governance["source"] if governance else None)
            self.assertIsNone(current_provider_dispatch_scope())

    async def test_harness_lifecycle_rejects_nonempty_invalid_profile_catalog(self) -> None:
        valid_route = {
            "profileKey": "gateway",
            "profileVersion": "v1",
            "endpoint": "https://gateway.example/v1",
            "model": "deep-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "isDefault": True,
        }
        invalid_routes: list[Any] = [{
            "profileKey": "malformed",
            "endpoint": "https://gateway.example/v1",
            "model": "intent-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
        }, "not-an-object", {
            "profileKey": "unsupported",
            "profileVersion": "v1",
            "endpoint": "https://gateway.example/v1",
            "model": "unsupported-model",
            "providerType": "anthropic",
            "protocol": "anthropic_messages",
        }, {
            "profileKey": "partial-capabilities",
            "profileVersion": "v1",
            "endpoint": "https://gateway.example/v1",
            "model": "partial-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "providerCapabilities": {
                "schemaVersion": 1,
                "supportsStreaming": True,
            },
        }]

        for invalid_route in invalid_routes:
            with self.subTest(invalid_route=invalid_route):
                routes = [valid_route, invalid_route]

                class FakeKnowledgeClient:
                    def __init__(self) -> None:
                        self.resolve_calls = 0

                    async def resolve_provider_dispatch(self, *_args: str) -> ProviderDispatch:
                        self.resolve_calls += 1
                        raise AssertionError("invalid catalog must fail before credential resolution")

                class FakeRuntime:
                    def __init__(self) -> None:
                        self.knowledge_client = FakeKnowledgeClient()

                    async def _load_agent_governance(self) -> dict[str, Any]:
                        return {"source": "backend", "config": {"providerProfiles": routes}}

                    @staticmethod
                    def _runtime_config_for_state(
                        governance: dict[str, Any],
                        config: dict[str, Any],
                    ) -> dict[str, Any]:
                        return {"source": governance.get("source"), **config}

                    @staticmethod
                    def _model_name(_request: KnowledgeChatRequest) -> str:
                        return "deep-model"

                runtime = FakeRuntime()
                harness = WebnovelHarness(runtime)  # type: ignore[arg-type]

                with self.assertRaisesRegex(ValueError, "provider profile catalog is invalid"):
                    async with harness._provider_dispatch_lifecycle(
                        KnowledgeChatRequest(question="test"),
                        None,
                    ):
                        self.fail("invalid catalog must not enter the dispatch lifecycle")

                self.assertEqual(0, runtime.knowledge_client.resolve_calls)
                self.assertIsNone(current_provider_dispatch_scope())

    async def test_frozen_catalog_allows_only_same_capability_set_profiles(self) -> None:
        calls: list[tuple[str, str]] = []
        capabilities = provider_capabilities()

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            calls.append((profile_key, profile_version))
            model = "intent-model" if profile_key == "intent" else "deep-model"
            return ProviderDispatch(
                profile_key=profile_key,
                profile_version=profile_version,
                endpoint="https://gateway.example/v1",
                model=model,
                provider_type="openai-compatible",
                protocol="responses",
                api_key=f"{profile_key}-secret",
                provider_capabilities=capabilities,
            )

        routes = [
            {
                "profileKey": "deep",
                "profileVersion": "v1",
                "endpoint": "https://gateway.example/v1",
                "model": "deep-model",
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": capabilities,
                "isDefault": True,
            },
            {
                "profileKey": "intent",
                "profileVersion": "v1",
                "endpoint": "https://gateway.example/v1",
                "model": "intent-model",
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": capabilities,
            },
            {
                "profileKey": "outside",
                "profileVersion": "v1",
                "endpoint": "https://other.example/v1",
                "model": "outside-model",
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": capabilities,
            },
        ]
        async with provider_dispatch_scope(resolver, routes=routes, preferred_model="deep-model"):
            intent = await resolve_provider_dispatch_for_model("intent-model")
            deep = await resolve_provider_dispatch_for_model("deep-model")
            self.assertEqual("intent", intent.profile_key)
            self.assertEqual("deep", deep.profile_key)
            with self.assertRaisesRegex(ValueError, "outside the frozen catalog"):
                await resolve_provider_dispatch("outside", "v1")

        self.assertEqual([("intent", "v1"), ("deep", "v1")], calls)

    async def test_preferred_profile_key_disambiguates_shared_model_name(self) -> None:
        capabilities = provider_capabilities()
        routes = [
            {
                "profileKey": key,
                "profileVersion": "v1",
                "endpoint": "https://gateway.example/v1",
                "model": "gpt-5.6-sol",
                "providerType": "openai",
                "protocol": "responses",
                "providerCapabilities": capabilities,
                "isDefault": key == "gpt-default",
            }
            for key in ("gpt-default", "gpt-selected")
        ]

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            return ProviderDispatch(
                profile_key=profile_key,
                profile_version=profile_version,
                endpoint="https://gateway.example/v1",
                model="gpt-5.6-sol",
                provider_type="openai",
                protocol="responses",
                api_key=f"{profile_key}-secret",
                provider_capabilities=capabilities,
            )

        async with provider_dispatch_scope(
            resolver,
            routes=routes,
            preferred_model="gpt-5.6-sol",
            preferred_profile_key="gpt-selected",
        ):
            selected = await resolve_provider_dispatch_for_model("gpt-5.6-sol")

        self.assertEqual("gpt-selected", selected.profile_key)
        self.assertEqual("gpt-selected-secret", selected.api_key)

    async def test_same_run_concurrent_profiles_do_not_mix_credentials(self) -> None:
        capabilities = provider_capabilities()
        routes = [
            {
                "profileKey": profile_key,
                "profileVersion": "v1",
                "endpoint": "https://gateway.example/v1",
                "model": model,
                "providerType": "openai-compatible",
                "protocol": "responses",
                "providerCapabilities": capabilities,
                "isDefault": profile_key == "deep",
            }
            for profile_key, model in (("intent", "intent-model"), ("deep", "deep-model"))
        ]
        calls: dict[str, int] = {}

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            calls[profile_key] = calls.get(profile_key, 0) + 1
            await asyncio.sleep(0)
            model = "intent-model" if profile_key == "intent" else "deep-model"
            return ProviderDispatch(
                profile_key=profile_key,
                profile_version=profile_version,
                endpoint="https://gateway.example/v1",
                model=model,
                provider_type="openai-compatible",
                protocol="responses",
                api_key=f"{profile_key}-secret",
                provider_capabilities=capabilities,
            )

        async with provider_dispatch_scope(resolver, routes=routes, preferred_model="deep-model"):
            intent, deep, intent_again = await asyncio.gather(
                resolve_provider_dispatch_for_model("intent-model"),
                resolve_provider_dispatch_for_model("deep-model"),
                resolve_provider_dispatch_for_model("intent-model"),
            )

        self.assertEqual("intent-secret", intent.api_key)
        self.assertEqual("deep-secret", deep.api_key)
        self.assertIs(intent, intent_again)
        self.assertEqual({"intent": 1, "deep": 1}, calls)

    async def test_harness_lifecycle_loads_new_run_catalog_once_and_resume_reuses_checkpoint(self) -> None:
        routes = [{
            "profileKey": "gateway",
            "profileVersion": "v1",
            "endpoint": "https://gateway.example/v1",
            "model": "deep-model",
            "providerType": "openai-compatible",
            "protocol": "responses",
            "isDefault": True,
        }]

        class FakeKnowledgeClient:
            def __init__(self) -> None:
                self.resolve_calls = 0

            async def resolve_provider_dispatch(self, profile_key: str, profile_version: str) -> ProviderDispatch:
                self.resolve_calls += 1
                return ProviderDispatch(
                    profile_key=profile_key,
                    profile_version=profile_version,
                    endpoint="https://gateway.example/v1",
                    model="deep-model",
                    provider_type="openai-compatible",
                    protocol="responses",
                    api_key="lifecycle-secret",
                )

        class FakeRuntime:
            def __init__(self) -> None:
                self.knowledge_client = FakeKnowledgeClient()
                self.governance_calls = 0

            async def _load_agent_governance(self) -> dict[str, Any]:
                self.governance_calls += 1
                return {
                    "source": "backend",
                    "config": {"providerProfiles": routes},
                    "errors": {"experts": "TimeoutError"},
                }

            @staticmethod
            def _runtime_config_for_state(governance: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
                return {"source": governance.get("source"), **config}

            @staticmethod
            def _model_name(_request: KnowledgeChatRequest) -> str:
                return "deep-model"

        runtime = FakeRuntime()
        harness = WebnovelHarness(runtime)  # type: ignore[arg-type]
        request = KnowledgeChatRequest(question="test")

        async with harness._provider_dispatch_lifecycle(request, None) as governance:
            dispatch = await resolve_provider_dispatch_for_model("deep-model")
            self.assertEqual("backend", governance["source"] if governance else None)
            self.assertEqual("gateway", dispatch.profile_key)
        self.assertIsNone(current_provider_dispatch_scope())
        self.assertEqual(1, runtime.governance_calls)
        self.assertEqual(1, runtime.knowledge_client.resolve_calls)

        checkpoint = (True, {"runtime_config": {"providerProfiles": routes}})
        async with harness._provider_dispatch_lifecycle(request, checkpoint) as governance:
            self.assertIsNone(governance)
            dispatch = await resolve_provider_dispatch_for_model("deep-model")
            self.assertEqual("gateway", dispatch.profile_key)
        self.assertEqual(1, runtime.governance_calls)
        self.assertEqual(2, runtime.knowledge_client.resolve_calls)

    async def test_concurrent_waiters_share_one_resolution_and_cached_dispatch(self) -> None:
        calls = 0
        started = asyncio.Event()
        release = asyncio.Event()

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            nonlocal calls
            calls += 1
            started.set()
            await release.wait()
            return dispatch_for(profile_key, profile_version, api_key="single-flight-secret")

        async with provider_dispatch_scope(resolver):
            first = asyncio.create_task(resolve_provider_dispatch("gateway-a", "version-a"))
            second = asyncio.create_task(resolve_provider_dispatch("gateway-a", "version-a"))
            await started.wait()
            self.assertEqual(1, calls)
            release.set()
            first_result, second_result = await asyncio.gather(first, second)
            cached_result = await resolve_provider_dispatch("gateway-a", "version-a")

            self.assertIs(first_result, second_result)
            self.assertIs(first_result, cached_result)
            self.assertIs(first_result, current_provider_dispatch())
            self.assertEqual(1, calls)

    async def test_nested_scopes_restore_outer_dispatch_and_clear_inner(self) -> None:
        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            return dispatch_for(profile_key, profile_version, api_key=f"{profile_key}-secret")

        async with provider_dispatch_scope(resolver) as outer_scope:
            outer = await resolve_provider_dispatch("outer", "v1")
            async with provider_dispatch_scope(resolver) as inner_scope:
                inner = await resolve_provider_dispatch("inner", "v2")
                self.assertIs(inner, current_provider_dispatch())
                self.assertIsNot(outer_scope, inner_scope)
            self.assertIs(outer, current_provider_dispatch())
            self.assertIsNone(inner_scope.current())

        self.assertIsNone(current_provider_dispatch_scope())
        self.assertIsNone(outer_scope.current())

    async def test_concurrent_scopes_do_not_share_dispatch_or_secret(self) -> None:
        ready = asyncio.Barrier(2)

        async def run(profile_key: str) -> tuple[str, str, str]:
            async def resolver(key: str, version: str) -> ProviderDispatch:
                await ready.wait()
                return dispatch_for(key, version, api_key=f"{key}-secret")

            async with provider_dispatch_scope(resolver):
                dispatch = await resolve_provider_dispatch(profile_key, "v1")
                current = current_provider_dispatch()
                assert current is not None
                return dispatch.profile_key, dispatch.api_key, current.profile_key

        first, second = await asyncio.gather(run("gateway-a"), run("gateway-b"))

        self.assertEqual(("gateway-a", "gateway-a-secret", "gateway-a"), first)
        self.assertEqual(("gateway-b", "gateway-b-secret", "gateway-b"), second)
        self.assertIsNone(current_provider_dispatch())

    async def test_failed_resolution_clears_single_flight_and_allows_retry(self) -> None:
        calls = 0

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            nonlocal calls
            calls += 1
            if calls == 1:
                raise RuntimeError("backend unavailable")
            return dispatch_for(profile_key, profile_version, api_key="retry-secret")

        async with provider_dispatch_scope(resolver) as scope:
            with self.assertRaisesRegex(RuntimeError, "backend unavailable"):
                await resolve_provider_dispatch("gateway-a", "v1")
            self.assertIsNone(scope.current())

            resolved = await resolve_provider_dispatch("gateway-a", "v1")
            self.assertEqual("retry-secret", resolved.api_key)
            self.assertEqual(2, calls)

    async def test_scope_rejects_route_change_and_cancels_inflight_on_exit(self) -> None:
        started = asyncio.Event()
        cancelled = asyncio.Event()

        async def resolver(profile_key: str, profile_version: str) -> ProviderDispatch:
            try:
                started.set()
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                cancelled.set()
                raise
            return dispatch_for(profile_key, profile_version, api_key="unused-secret")

        async with provider_dispatch_scope(resolver):
            inflight = asyncio.create_task(resolve_provider_dispatch("gateway-a", "v1"))
            await started.wait()
            with self.assertRaisesRegex(ValueError, "route mismatch"):
                await resolve_provider_dispatch("gateway-b", "v2")

        await asyncio.wait_for(cancelled.wait(), timeout=1)
        with self.assertRaises(asyncio.CancelledError):
            await inflight
        self.assertIsNone(current_provider_dispatch())


if __name__ == "__main__":
    unittest.main()
