from __future__ import annotations

import unittest

from app.services.knowledge_client import KnowledgeBackendClient


class KnowledgeBackendClientTest(unittest.TestCase):
    def test_should_use_explicit_base_url_when_provided(self) -> None:
        client = KnowledgeBackendClient(
            base_url="http://127.0.0.1:8080",
            internal_api_key="worker-test-key",
        )

        self.assertEqual("http://127.0.0.1:8080", client.base_url)
        self.assertEqual("worker-test-key", client.internal_api_key)

    def test_should_use_separate_backend_tool_timeout(self) -> None:
        client = KnowledgeBackendClient(
            base_url="http://127.0.0.1:8080",
            internal_api_key="worker-test-key",
        )

        self.assertGreaterEqual(client.timeout_seconds, 60)


if __name__ == "__main__":
    unittest.main()
