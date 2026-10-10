"""Phase 171: intelligent retry and chain failover in the agent kernel.

Covers the shared 5-attempt budget, immediate key switching on credential
failures, exponential backoff with jitter, and the streaming first-delta
boundary that forbids transparent replay.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, Mock, patch

import httpx
import pytest

from app.services.harness.agent_kernel import AgentKernel, FailureUrgency, ProviderAttemptTrace
from app.services.provider_client import OpenAICompatibleProviderClient


def _http_error(status: int, headers: dict[str, str] | None = None) -> httpx.HTTPStatusError:
    request = httpx.Request("POST", "https://upstream.invalid/v1/chat/completions")
    response = httpx.Response(status, headers=headers or {}, request=request)
    return httpx.HTTPStatusError(f"HTTP {status}", request=request, response=response)


def _kernel_with_client(**invoke_kwargs) -> tuple[AgentKernel, AsyncMock]:
    """Kernel whose client keeps the real synchronous failover classifier.

    An AsyncMock would turn ``failover_failure_class`` into a coroutine and the
    kernel would report a nonsense failure class, hiding real regressions.
    """
    client = AsyncMock()
    client.failover_failure_class = Mock(
        side_effect=OpenAICompatibleProviderClient().failover_failure_class
    )
    if invoke_kwargs:
        client.invoke = AsyncMock(**invoke_kwargs)
    kernel = AgentKernel(provider_client=client)
    kernel._report_provider_outcome = AsyncMock()
    return kernel, client


class TestFailureClassification:
    def test_connect_error_is_transient(self):
        assert AgentKernel._classify_failure(httpx.ConnectError("refused")) is FailureUrgency.TRANSIENT

    def test_timeout_is_transient(self):
        assert AgentKernel._classify_failure(httpx.ReadTimeout("slow")) is FailureUrgency.TRANSIENT

    @pytest.mark.parametrize("status", [401, 403])
    def test_credential_statuses(self, status):
        assert AgentKernel._classify_failure(_http_error(status)) is FailureUrgency.INVALID_CREDENTIALS

    @pytest.mark.parametrize("status", [402, 429])
    def test_quota_statuses(self, status):
        assert AgentKernel._classify_failure(_http_error(status)) is FailureUrgency.INSUFFICIENT_QUOTA

    def test_404_is_model_not_found(self):
        assert AgentKernel._classify_failure(_http_error(404)) is FailureUrgency.MODEL_NOT_FOUND

    @pytest.mark.parametrize("status", [500, 502, 503, 504])
    def test_server_statuses_are_transient(self, status):
        assert AgentKernel._classify_failure(_http_error(status)) is FailureUrgency.TRANSIENT

    @pytest.mark.parametrize("status", [400, 422])
    def test_malformed_request_is_permanent(self, status):
        assert AgentKernel._classify_failure(_http_error(status)) is FailureUrgency.PERMANENT

    def test_unmapped_status_is_unknown(self):
        assert AgentKernel._classify_failure(_http_error(418)) is FailureUrgency.UNKNOWN

    def test_non_http_error_is_unknown(self):
        assert AgentKernel._classify_failure(ValueError("boom")) is FailureUrgency.UNKNOWN


class TestBackoffStrategy:
    def test_grows_exponentially_from_half_a_second(self):
        error = httpx.ReadTimeout("slow")
        assert 0.5 <= AgentKernel._backoff_seconds(0, error) <= 0.65
        assert 1.0 <= AgentKernel._backoff_seconds(1, error) <= 1.3
        assert 2.0 <= AgentKernel._backoff_seconds(2, error) <= 2.6

    def test_has_jitter(self):
        error = httpx.ReadTimeout("slow")
        samples = {AgentKernel._backoff_seconds(3, error) for _ in range(40)}
        assert len(samples) > 1, "backoff must be jittered, not a fixed ladder"

    def test_retry_after_header_is_a_floor(self):
        error = _http_error(429, {"Retry-After": "10"})
        assert AgentKernel._backoff_seconds(0, error) >= 10.0

    def test_non_numeric_retry_after_is_ignored(self):
        error = _http_error(429, {"Retry-After": "Wed, 21 Oct 2026 07:28:00 GMT"})
        assert AgentKernel._backoff_seconds(0, error) <= 0.65

    def test_capped_at_a_minute(self):
        assert AgentKernel._backoff_seconds(10, httpx.ReadTimeout("slow")) <= 78.0


_OK = {"choices": [{"message": {"content": "ok"}}]}


@pytest.mark.asyncio
class TestInvokeBudget:
    async def test_success_on_first_attempt_spends_nothing(self):
        kernel, client = _kernel_with_client(return_value=_OK)

        result, profile, trace = await kernel._invoke_provider_with_retry_and_failover(
            {"messages": []}, Mock(model="gpt-4"), budget=5
        )

        assert result == _OK
        assert trace.attempt_index == 1
        assert client.invoke.call_count == 1

    async def test_credential_failure_switches_key_without_spending_budget(self):
        kernel, client = _kernel_with_client(
            side_effect=[_http_error(401)] * 4 + [httpx.ReadTimeout("slow")] * 4 + [_OK]
        )
        keys = [Mock(model="gpt-4", name=f"key{index}") for index in range(5)]
        pending = keys[1:]
        kernel._claim_failover = AsyncMock(side_effect=lambda *_: pending.pop(0) if pending else None)

        with patch("asyncio.sleep", new_callable=AsyncMock):
            result, profile, trace = await kernel._invoke_provider_with_retry_and_failover(
                {"messages": []}, keys[0], budget=5
            )

        # The four 401s spent zero budget, so the last key still got all five attempts.
        assert result == _OK
        assert trace.attempt_index == 9
        assert profile is keys[4]
        assert kernel._claim_failover.call_count == 4

    async def test_transient_failures_retry_the_same_key(self):
        kernel, client = _kernel_with_client(
            side_effect=[httpx.ReadTimeout("slow"), httpx.ReadTimeout("slow"), _OK]
        )
        kernel._claim_failover = AsyncMock(return_value=None)
        profile = Mock(model="gpt-4")

        with patch("asyncio.sleep", new_callable=AsyncMock):
            result, final_profile, trace = await kernel._invoke_provider_with_retry_and_failover(
                {"messages": []}, profile, budget=5
            )

        assert result == _OK
        assert trace.attempt_index == 3
        assert final_profile is profile
        kernel._claim_failover.assert_not_called()

    async def test_budget_exhaustion_makes_one_last_attempt_on_the_next_key(self):
        kernel, client = _kernel_with_client(side_effect=[httpx.ReadTimeout("slow")] * 5 + [_OK])
        primary, backup = Mock(model="gpt-4"), Mock(model="gpt-4")
        kernel._claim_failover = AsyncMock(return_value=backup)

        with patch("asyncio.sleep", new_callable=AsyncMock):
            result, final_profile, trace = await kernel._invoke_provider_with_retry_and_failover(
                {"messages": []}, primary, budget=5
            )

        assert result == _OK
        assert final_profile is backup
        assert trace.attempt_index == 6
        assert kernel._claim_failover.call_count == 1

    async def test_budget_exhaustion_without_a_backup_raises(self):
        kernel, client = _kernel_with_client(side_effect=[httpx.ReadTimeout("slow")] * 5)
        kernel._claim_failover = AsyncMock(return_value=None)

        with patch("asyncio.sleep", new_callable=AsyncMock):
            with pytest.raises(httpx.ReadTimeout):
                await kernel._invoke_provider_with_retry_and_failover(
                    {"messages": []}, Mock(model="gpt-4"), budget=5
                )

        assert client.invoke.call_count == 5

    async def test_malformed_request_neither_retries_nor_switches(self):
        kernel, client = _kernel_with_client(side_effect=_http_error(400))
        kernel._claim_failover = AsyncMock(return_value=Mock(model="gpt-4"))

        with pytest.raises(httpx.HTTPStatusError):
            await kernel._invoke_provider_with_retry_and_failover(
                {"messages": []}, Mock(model="gpt-4"), budget=5
            )

        assert client.invoke.call_count == 1
        kernel._claim_failover.assert_not_called()

    async def test_five_key_chain_is_fully_traversed(self):
        kernel, client = _kernel_with_client(side_effect=[_http_error(401)] * 4 + [_OK])
        keys = [Mock(model="gpt-4") for _ in range(5)]
        pending = keys[1:]
        kernel._claim_failover = AsyncMock(side_effect=lambda *_: pending.pop(0) if pending else None)

        result, final_profile, trace = await kernel._invoke_provider_with_retry_and_failover(
            {"messages": []}, keys[0], budget=5
        )

        assert result == _OK
        assert final_profile is keys[4]
        assert trace.attempt_index == 5
        assert kernel._claim_failover.call_count == 4

    async def test_exhausted_key_list_raises_the_last_error(self):
        kernel, client = _kernel_with_client(side_effect=[_http_error(401)] * 3)
        kernel._claim_failover = AsyncMock(side_effect=[Mock(model="gpt-4"), Mock(model="gpt-4"), None])

        with pytest.raises(httpx.HTTPStatusError):
            await kernel._invoke_provider_with_retry_and_failover(
                {"messages": []}, Mock(model="gpt-4"), budget=5
            )

        assert client.invoke.call_count == 3


class TestRetryWindow:
    def test_defaults_to_a_floor_without_a_request_timeout(self):
        assert AgentKernel._retry_window_seconds({}) == 10.0

    def test_tracks_the_request_timeout(self):
        assert AgentKernel._retry_window_seconds({"timeout_millis": 30000}) == 30.0

    def test_ignores_unusable_timeouts(self):
        assert AgentKernel._retry_window_seconds({"timeout_millis": "nonsense"}) == 10.0
        assert AgentKernel._retry_window_seconds({"timeout_millis": 0}) == 10.0


@pytest.mark.asyncio
class TestRetryWallClock:
    """The loop must not keep spending attempts nobody is waiting for."""

    async def test_attempt_slower_than_the_window_is_not_retried(self):
        kernel, client = _kernel_with_client()
        # One attempt burns the whole window, so the caller has already timed out.
        clock = iter([0.0, 31.0, 31.0, 31.0])
        client.invoke = AsyncMock(side_effect=httpx.ReadTimeout("slow"))
        kernel._claim_failover = AsyncMock(return_value=Mock(model="gpt-4"))

        with patch("time.monotonic", side_effect=lambda: next(clock)):
            with pytest.raises(httpx.ReadTimeout):
                await kernel._invoke_provider_with_retry_and_failover(
                    {"messages": [], "timeout_millis": 30000}, Mock(model="gpt-4"), budget=5
                )

        assert client.invoke.call_count == 1
        kernel._claim_failover.assert_not_called()

    async def test_fast_failures_still_use_the_whole_budget(self):
        kernel, client = _kernel_with_client(
            side_effect=[httpx.ConnectError("refused")] * 4 + [_OK]
        )
        kernel._claim_failover = AsyncMock(return_value=None)

        with patch("asyncio.sleep", new_callable=AsyncMock):
            result, _profile, trace = await kernel._invoke_provider_with_retry_and_failover(
                {"messages": [], "timeout_millis": 30000}, Mock(model="gpt-4"), budget=5
            )

        assert result == _OK
        assert trace.attempt_index == 5


async def _stream_of(*events):
    for event in events:
        yield event


async def _failing_stream(error, *events):
    for event in events:
        yield event
    raise error


@pytest.mark.asyncio
class TestStreamBudget:
    async def _drain(self, kernel, stream_fn, profile, budget=5, kwargs=None):
        collected = []
        async for event, active, trace in kernel._stream_provider_with_failover(
            stream_fn, kwargs or {"messages": []}, profile, budget=budget
        ):
            collected.append((event, active, trace))
        return collected

    async def test_clean_stream_reports_success(self):
        kernel, _client = _kernel_with_client()
        stream_fn = Mock(side_effect=lambda **_: _stream_of("a", "b"))

        collected = await self._drain(kernel, stream_fn, Mock(model="gpt-4"))

        assert [event for event, _, _ in collected] == ["a", "b"]
        assert stream_fn.call_count == 1

    async def test_failure_before_first_delta_switches_key(self):
        kernel, _client = _kernel_with_client()
        primary, backup = Mock(model="gpt-4"), Mock(model="gpt-4")
        kernel._claim_failover = AsyncMock(return_value=backup)
        streams = [_failing_stream(_http_error(401)), _stream_of("a")]
        stream_fn = Mock(side_effect=lambda **_: streams.pop(0))

        collected = await self._drain(kernel, stream_fn, primary)

        assert [event for event, _, _ in collected] == ["a"]
        assert [active for _, active, _ in collected] == [backup]
        assert stream_fn.call_count == 2

    async def test_failure_after_first_delta_raises_instead_of_switching(self):
        kernel, _client = _kernel_with_client()
        kernel._claim_failover = AsyncMock(return_value=Mock(model="gpt-4"))
        stream_fn = Mock(side_effect=lambda **_: _failing_stream(_http_error(502), "a"))

        with pytest.raises(httpx.HTTPStatusError):
            await self._drain(kernel, stream_fn, Mock(model="gpt-4"))

        assert stream_fn.call_count == 1
        kernel._claim_failover.assert_not_called()

    async def test_transient_failure_before_first_delta_retries_same_key(self):
        kernel, _client = _kernel_with_client()
        kernel._claim_failover = AsyncMock(return_value=None)
        profile = Mock(model="gpt-4")
        streams = [_failing_stream(httpx.ReadTimeout("slow")), _stream_of("a")]
        stream_fn = Mock(side_effect=lambda **_: streams.pop(0))

        with patch("asyncio.sleep", new_callable=AsyncMock):
            collected = await self._drain(kernel, stream_fn, profile)

        assert [event for event, _, _ in collected] == ["a"]
        assert [active for _, active, _ in collected] == [profile]
        kernel._claim_failover.assert_not_called()

    async def test_budget_exhaustion_hands_the_stream_to_the_next_key(self):
        kernel, _client = _kernel_with_client()
        backup = Mock(model="gpt-4")
        kernel._claim_failover = AsyncMock(return_value=backup)
        streams = [_failing_stream(_http_error(503)) for _ in range(5)] + [_stream_of("a")]
        stream_fn = Mock(side_effect=lambda **_: streams.pop(0))

        with patch("asyncio.sleep", new_callable=AsyncMock):
            collected = await self._drain(kernel, stream_fn, Mock(model="gpt-4"))

        assert [event for event, _, _ in collected] == ["a"]
        assert [active for _, active, _ in collected] == [backup]
        assert stream_fn.call_count == 6
        assert kernel._claim_failover.call_count == 1

    async def test_malformed_request_neither_retries_nor_switches(self):
        kernel, _client = _kernel_with_client()
        kernel._claim_failover = AsyncMock(return_value=Mock(model="gpt-4"))
        stream_fn = Mock(side_effect=lambda **_: _failing_stream(_http_error(400)))

        with pytest.raises(httpx.HTTPStatusError):
            await self._drain(kernel, stream_fn, Mock(model="gpt-4"))

        assert stream_fn.call_count == 1
        kernel._claim_failover.assert_not_called()

    async def test_retrying_stops_once_the_window_is_spent(self):
        kernel, _client = _kernel_with_client()
        kernel._claim_failover = AsyncMock(return_value=Mock(model="gpt-4"))
        stream_fn = Mock(side_effect=lambda **_: _failing_stream(httpx.ReadTimeout("slow")))
        # The first attempt burns the whole 30s window the caller was willing to wait.
        ticks = iter([0.0])

        with patch("time.monotonic", side_effect=lambda: next(ticks, 31.0)):
            with pytest.raises(httpx.ReadTimeout):
                await self._drain(
                    kernel,
                    stream_fn,
                    Mock(model="gpt-4"),
                    kwargs={"messages": [], "timeout_millis": 30000},
                )

        assert stream_fn.call_count == 1
        kernel._claim_failover.assert_not_called()


class TestAttemptTraceSummary:
    def test_omits_a_clean_first_attempt(self):
        assert ProviderAttemptTrace(attempt_index=1).trace_summary() == {"attemptIndex": 1}

    def test_carries_key_and_failure_class(self):
        summary = ProviderAttemptTrace(
            attempt_index=3, profile_key="gateway-b", failure_class="HTTP_401"
        ).trace_summary()

        assert summary == {
            "attemptIndex": 3,
            "profileKeyUsed": "gateway-b",
            "failureClass": "HTTP_401",
        }


@pytest.mark.asyncio
class TestAttemptTrace:
    """The run panel can only show a failover if the kernel records one."""

    async def test_blocking_trace_records_the_switch_that_saved_the_call(self):
        kernel, _client = _kernel_with_client(side_effect=[_http_error(401), _OK])
        backup = Mock(model="gpt-4", profile_key="gateway-b", profile_version="v2")
        kernel._claim_failover = AsyncMock(return_value=backup)
        primary = Mock(model="gpt-4", profile_key="gateway-a", profile_version="v1")

        _result, _profile, trace = await kernel._invoke_provider_with_retry_and_failover(
            {"messages": []}, primary, budget=5
        )

        assert trace.trace_summary() == {
            "attemptIndex": 2,
            "profileKeyUsed": "gateway-b",
            "failureClass": "HTTP_401",
        }

    async def test_blocking_trace_is_quiet_when_nothing_went_wrong(self):
        kernel, _client = _kernel_with_client(return_value=_OK)
        profile = Mock(model="gpt-4", profile_key="gateway-a", profile_version="v1")

        _result, _profile, trace = await kernel._invoke_provider_with_retry_and_failover(
            {"messages": []}, profile, budget=5
        )

        assert trace.failure_class is None
        assert trace.trace_summary() == {"attemptIndex": 1, "profileKeyUsed": "gateway-a"}

    async def test_stream_trace_is_frozen_at_the_first_delta(self):
        kernel, _client = _kernel_with_client()
        backup = Mock(model="gpt-4", profile_key="gateway-b", profile_version="v2")
        kernel._claim_failover = AsyncMock(return_value=backup)
        streams = [_failing_stream(_http_error(429)), _stream_of("a", "b")]
        stream_fn = Mock(side_effect=lambda **_: streams.pop(0))
        primary = Mock(model="gpt-4", profile_key="gateway-a", profile_version="v1")

        with patch("asyncio.sleep", new_callable=AsyncMock):
            collected = await TestStreamBudget()._drain(kernel, stream_fn, primary)

        # Every event of the surviving stream reports the same attempt, not a moving one.
        assert [trace.trace_summary() for _, _, trace in collected] == [
            {"attemptIndex": 2, "profileKeyUsed": "gateway-b", "failureClass": "HTTP_429"},
        ] * 2
