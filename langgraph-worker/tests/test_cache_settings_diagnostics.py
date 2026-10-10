from copy import deepcopy
import unittest

from app.services.harness.agent_kernel import AgentKernel
from app.services.provider_client import OpenAICompatibleProviderClient


class CacheSettingsDiagnosticsTest(unittest.TestCase):
    def test_semantic_wire_fields_change_diagnostics_without_changing_input_or_cache_identity(self) -> None:
        client = OpenAICompatibleProviderClient()
        payload = {
            "model": "fixture", "messages": [{"role": "system", "content": "stable fixture"},
                                                {"role": "user", "content": "question fixture"}],
            "stream": False, "prompt_cache_key": "fixture-cache-identity",
        }
        frozen = deepcopy(payload)
        baseline = client._cache_continuity_snapshot(payload, "chat_completions", cache_affinity="fixture-affinity")
        for field, value in {
            "temperature": 0.7, "max_tokens": 200, "max_completion_tokens": 300,
            "max_output_tokens": 400, "response_format": {"type": "json_object"},
            "thinking": {"type": "enabled"}, "reasoning_effort": "high", "enable_thinking": True,
            "tool_choice": "auto",
        }.items():
            with self.subTest(field=field):
                changed = client._cache_continuity_snapshot(
                    {**payload, field: value}, "chat_completions", cache_affinity="fixture-affinity",
                )
                self.assertNotEqual(baseline["requestSettingsFingerprint"], changed["requestSettingsFingerprint"])
                for unchanged in ("stablePrefixFingerprint", "inputFingerprint", "affinityFingerprint", "toolsFingerprint"):
                    self.assertEqual(baseline[unchanged], changed[unchanged])
        self.assertEqual(frozen, payload)
        self.assertEqual(2, baseline["requestSettingsVersion"])
        sanitized = AgentKernel._sanitize_cache_continuity(baseline)
        self.assertEqual(2, sanitized["requestSettingsVersion"])
        self.assertNotIn("fixture-cache-identity", str(sanitized))

    def test_transport_and_host_metadata_do_not_change_semantic_settings(self) -> None:
        client = OpenAICompatibleProviderClient()
        payload = {"model": "fixture", "messages": [], "temperature": 0.2}
        baseline = client._cache_continuity_snapshot(payload, "chat_completions")
        changed = client._cache_continuity_snapshot({**payload, "stream": True,
            "stream_options": {"include_usage": True}, "itemId": "host-only", "cursor": 9}, "chat_completions")
        self.assertEqual(baseline["requestSettingsFingerprint"], changed["requestSettingsFingerprint"])
