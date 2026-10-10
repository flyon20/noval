import asyncio
import os
import unittest

from app.services.provider_client import OpenAICompatibleProviderClient, ProviderProfile


RUN_E2E = os.getenv("NOVAL_RUN_DEEPSEEK_CACHE_E2E", "").strip() == "1"
DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "").strip()


@unittest.skipUnless(
    RUN_E2E and bool(DEEPSEEK_API_KEY),
    "set NOVAL_RUN_DEEPSEEK_CACHE_E2E=1 and DEEPSEEK_API_KEY to run",
)
class DeepSeekCacheE2ETest(unittest.IsolatedAsyncioTestCase):
    async def test_responses_cache_observes_at_least_one_best_effort_hit(self) -> None:
        base_url = os.getenv(
            "NOVAL_DEEPSEEK_CACHE_E2E_BASE_URL",
            "https://api.deepseek.com",
        ).strip()
        model = os.getenv(
            "NOVAL_DEEPSEEK_CACHE_E2E_MODEL",
            "deepseek-v4-pro",
        ).strip()
        wait_seconds = self._bounded_env_float(
            "NOVAL_DEEPSEEK_CACHE_E2E_WAIT_SECONDS",
            default=5.0,
            minimum=1.0,
            maximum=30.0,
        )
        sample_count = self._bounded_env_int(
            "NOVAL_DEEPSEEK_CACHE_E2E_SAMPLES",
            default=3,
            minimum=2,
            maximum=8,
        )
        profile = ProviderProfile(
            profile_key="deepseek-cache-e2e",
            profile_version="1",
            endpoint=base_url,
            model=model,
            protocol="responses",
            api_key=DEEPSEEK_API_KEY,
        )
        client = OpenAICompatibleProviderClient()
        stable_prefix = (
            "This is a controlled cache-prefix observation. Preserve every byte of this "
            "instruction and answer each user request with one short plain-text sentence. "
        ) * 160
        messages = [
            {"role": "system", "content": stable_prefix},
            {"role": "user", "content": "Return the word warmup."},
        ]

        results = []
        for index in range(sample_count):
            result = await client.invoke(
                messages=messages,
                model=model,
                temperature=0,
                max_tokens=32,
                require_json=False,
                provider_profile=profile,
            )
            results.append(result)
            if index < sample_count - 1:
                messages = [
                    *messages,
                    {"role": "assistant", "content": str(result.get("content") or "")},
                    {"role": "user", "content": f"Return the word sample-{index + 2}."},
                ]
                await asyncio.sleep(wait_seconds)

        observed_hit_tokens = [
            max(0, int((result.get("usage") or {}).get("promptCacheHitTokens") or 0))
            for result in results[1:]
        ]
        self.assertTrue(
            any(tokens > 0 for tokens in observed_hit_tokens),
            "DeepSeek cache is best-effort, but no effective hit was observed after warm-up",
        )
        self.assertTrue(all(result.get("wire_api") == "responses" for result in results))
        self.assertTrue(
            all("providerTransportFallback" not in result for result in results),
        )

    @staticmethod
    def _bounded_env_float(
        name: str,
        *,
        default: float,
        minimum: float,
        maximum: float,
    ) -> float:
        try:
            value = float(os.getenv(name, str(default)))
        except ValueError:
            value = default
        return min(max(value, minimum), maximum)

    @staticmethod
    def _bounded_env_int(
        name: str,
        *,
        default: int,
        minimum: int,
        maximum: int,
    ) -> int:
        try:
            value = int(os.getenv(name, str(default)))
        except ValueError:
            value = default
        return min(max(value, minimum), maximum)
