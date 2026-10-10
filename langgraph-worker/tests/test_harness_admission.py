import asyncio
import unittest

from app.config import settings
from app.services.harness.admission import (
    ADMISSION_POLL_INTERVAL_SECONDS,
    ProcessSemaphore,
    delegation_slot,
    get_delegation_semaphore,
    get_llm_semaphore,
    get_run_semaphore,
    llm_slot,
    run_slot,
)
from app.services.harness.budget import (
    BudgetExceededError,
    RunBudget,
    current_run_budget,
    run_budget_scope,
)
from app.services.harness.cancellation import (
    CancellationToken,
    RunCancelledError,
    cancellable_await,
    cancellation_checkpoint,
    cancellation_scope,
    current_cancellation_token,
)
from app.services.harness.context_policy import context_policy_scope


class HarnessAdmissionTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.original_fast_runs = settings.max_active_fast_runs
        self.original_deep_runs = settings.max_active_deep_runs

    async def asyncTearDown(self) -> None:
        settings.max_active_fast_runs = self.original_fast_runs
        settings.max_active_deep_runs = self.original_deep_runs
        get_run_semaphore("fast")
        get_run_semaphore("deep")

    async def test_cancelled_acquire_does_not_leak_process_slot(self) -> None:
        semaphore = ProcessSemaphore(1)
        await semaphore.acquire()
        token = CancellationToken()

        waiter = asyncio.create_task(semaphore.acquire(token=token))
        await asyncio.sleep(0)
        semaphore.release()
        token.cancel("cancelled_during_admission")

        with self.assertRaisesRegex(RunCancelledError, "cancelled_during_admission"):
            await waiter
        self.assertEqual(1, semaphore._value)

    async def test_saturated_admission_uses_low_frequency_waits(self) -> None:
        self.assertGreaterEqual(ADMISSION_POLL_INTERVAL_SECONDS, 0.05)

    async def test_process_shared_semaphores_use_approved_settings(self) -> None:
        llm_semaphore = get_llm_semaphore()
        delegation_semaphore = get_delegation_semaphore()
        fast_run_semaphore = get_run_semaphore("fast")
        deep_run_semaphore = get_run_semaphore("deep")

        self.assertIs(llm_semaphore, get_llm_semaphore())
        self.assertIs(delegation_semaphore, get_delegation_semaphore())
        self.assertIs(fast_run_semaphore, get_run_semaphore("fast"))
        self.assertIs(deep_run_semaphore, get_run_semaphore("deep"))
        self.assertEqual(settings.max_active_llm_calls, llm_semaphore._value)
        self.assertEqual(settings.max_delegated_agent_concurrency, delegation_semaphore._value)
        self.assertEqual(settings.max_active_fast_runs, fast_run_semaphore._value)
        self.assertEqual(settings.max_active_deep_runs, deep_run_semaphore._value)

    async def test_fast_and_deep_runs_use_separate_capacity_pools(self) -> None:
        settings.max_active_fast_runs = 2
        settings.max_active_deep_runs = 1
        fast_release = asyncio.Event()
        deep_release = asyncio.Event()
        fast_entered = 0
        deep_entered = 0

        async def hold_fast(run_id: str) -> None:
            nonlocal fast_entered
            async with run_slot("fast", run_id=run_id):
                fast_entered += 1
                await fast_release.wait()

        async def hold_deep(run_id: str) -> None:
            nonlocal deep_entered
            async with run_slot("deep", run_id=run_id):
                deep_entered += 1
                await deep_release.wait()

        tasks = [
            *(asyncio.create_task(hold_fast(f"fast-{index}")) for index in range(3)),
            *(asyncio.create_task(hold_deep(f"deep-{index}")) for index in range(2)),
        ]
        await asyncio.sleep(0.05)

        self.assertEqual(2, fast_entered)
        self.assertEqual(1, deep_entered)

        fast_release.set()
        deep_release.set()
        await asyncio.gather(*tasks)
        self.assertEqual(3, fast_entered)
        self.assertEqual(2, deep_entered)

    async def test_cancelled_run_waiter_does_not_leak_slot(self) -> None:
        settings.max_active_fast_runs = 1
        semaphore = get_run_semaphore("fast")
        await semaphore.acquire()
        token = CancellationToken()

        async def wait_for_run() -> None:
            with cancellation_scope(token):
                async with run_slot("fast", run_id="cancelled-run"):
                    self.fail("cancelled run waiter entered the slot")

        waiter = asyncio.create_task(wait_for_run())
        await asyncio.sleep(0)
        token.cancel("run_admission_cancelled")

        with self.assertRaisesRegex(RunCancelledError, "run_admission_cancelled"):
            await waiter
        semaphore.release()
        self.assertEqual(1, semaphore._value)

    async def test_run_slot_releases_after_exception(self) -> None:
        settings.max_active_deep_runs = 1
        semaphore = get_run_semaphore("deep")

        with self.assertRaisesRegex(RuntimeError, "run failed"):
            async with run_slot("deep", run_id="failing-run"):
                self.assertEqual(0, semaphore._value)
                raise RuntimeError("run failed")

        self.assertEqual(1, semaphore._value)

    async def test_same_run_can_reenter_without_double_occupying_pool(self) -> None:
        settings.max_active_fast_runs = 1
        semaphore = get_run_semaphore("fast")

        async with run_slot("fast", run_id="same-run"):
            self.assertEqual(0, semaphore._value)
            async with run_slot("fast", run_id="same-run"):
                self.assertEqual(0, semaphore._value)

        self.assertEqual(1, semaphore._value)

    async def test_admission_slots_block_after_configured_capacity(self) -> None:
        llm_entered = 0
        delegation_entered = 0

        async def hold_llm(release: asyncio.Event) -> None:
            nonlocal llm_entered
            async with llm_slot():
                llm_entered += 1
                await release.wait()

        async def hold_delegation(release: asyncio.Event) -> None:
            nonlocal delegation_entered
            async with delegation_slot():
                delegation_entered += 1
                await release.wait()

        llm_release = asyncio.Event()
        llm_tasks = [
            asyncio.create_task(hold_llm(llm_release))
            for _ in range(settings.max_active_llm_calls + 1)
        ]
        delegation_release = asyncio.Event()
        delegation_tasks = [
            asyncio.create_task(hold_delegation(delegation_release))
            for _ in range(settings.max_delegated_agent_concurrency + 1)
        ]

        await asyncio.sleep(0)
        self.assertEqual(settings.max_active_llm_calls, llm_entered)
        self.assertEqual(settings.max_delegated_agent_concurrency, delegation_entered)

        llm_release.set()
        delegation_release.set()
        await asyncio.gather(*llm_tasks, *delegation_tasks)

        llm_semaphore = get_llm_semaphore()
        for _ in range(settings.max_active_llm_calls):
            await llm_semaphore.acquire()
        try:
            token = CancellationToken()

            async def wait_for_llm_slot() -> None:
                with cancellation_scope(token):
                    async with llm_slot():
                        self.fail("cancelled admission waiter entered the slot")

            waiter = asyncio.create_task(wait_for_llm_slot())
            await asyncio.sleep(0)
            token.cancel("admission_cancelled")

            with self.assertRaisesRegex(RunCancelledError, "admission_cancelled"):
                await waiter
        finally:
            for _ in range(settings.max_active_llm_calls):
                llm_semaphore.release()


class RunBudgetTest(unittest.IsolatedAsyncioTestCase):
    async def test_fast_and_deep_defaults_preserve_large_total_token_ceiling(self) -> None:
        fast = RunBudget.for_mode("fast")
        deep = RunBudget.for_mode("deep")

        self.assertEqual((128_000, 6, 1), fast.limits)
        self.assertEqual((512_000, 12, 2), deep.limits)

    async def test_model_context_window_scales_run_budget_above_one_full_window(self) -> None:
        # 0.5 倍窗口是降级根因：压缩后的单次输入几乎就等于窗口的 85%，
        # 第一刀就吃光预算。默认改成 1.5 倍，留出复核/意图等后续调用的空间。
        fast = RunBudget.for_mode("fast", context_window_tokens=300_000)
        deep = RunBudget.for_mode("deep", context_window_tokens=300_000)
        smaller = RunBudget.for_mode("fast", context_window_tokens=128_000)

        self.assertEqual((450_000, 6, 1), fast.limits)
        self.assertEqual((450_000, 12, 2), deep.limits)
        self.assertEqual((192_000, 6, 1), smaller.limits)

    async def test_run_policy_percent_overrides_budget_share(self) -> None:
        with context_policy_scope({"runTokenBudgetPercent": 200}):
            self.assertEqual(
                600_000,
                RunBudget.for_mode("fast", context_window_tokens=300_000).max_total_tokens,
            )
        # 出了 scope 回到默认 1.5 倍。
        self.assertEqual(
            450_000,
            RunBudget.for_mode("fast", context_window_tokens=300_000).max_total_tokens,
        )

    async def test_budget_consumption_is_atomic_when_a_limit_is_exceeded(self) -> None:
        budget = RunBudget.for_mode("fast")

        budget.consume_tokens(127_999)
        budget.consume_tool_call(6)
        budget.consume_delegation()

        with self.assertRaises(BudgetExceededError):
            budget.consume_tokens(2)
        with self.assertRaises(BudgetExceededError):
            budget.consume_tool_call()
        with self.assertRaises(BudgetExceededError):
            budget.consume_delegation()

        self.assertEqual((1, 0, 0), budget.remaining)
        self.assertEqual((127_999, 6, 1), budget.consumed)

    async def test_provider_usage_overrun_marks_token_budget_exhausted(self) -> None:
        budget = RunBudget(
            mode="fast",
            max_total_tokens=10,
            max_tool_calls=6,
            max_delegations=1,
        )
        budget.consume_tokens(9)

        with self.assertRaises(BudgetExceededError):
            budget.record_tokens(2)
        with self.assertRaises(BudgetExceededError):
            budget.require_token_capacity()

        self.assertEqual(10, budget.used_total_tokens)

    async def test_run_budget_scope_is_inherited_and_restored(self) -> None:
        self.assertIsNone(current_run_budget())

        with run_budget_scope("deep") as budget:
            self.assertIs(budget, current_run_budget())
            inherited = await asyncio.create_task(self._consume_current_budget())
            self.assertIs(budget, inherited)
            self.assertEqual((100, 1, 1), budget.consumed)

            with run_budget_scope("fast") as nested:
                self.assertIs(nested, current_run_budget())

            self.assertIs(budget, current_run_budget())

        self.assertIsNone(current_run_budget())

    async def test_negative_consumption_is_rejected_without_mutation(self) -> None:
        budget = RunBudget.for_mode("fast")

        for consume in (
            budget.consume_tokens,
            budget.consume_tool_call,
            budget.consume_delegation,
        ):
            with self.subTest(consume=consume.__name__):
                with self.assertRaises(ValueError):
                    consume(-1)

        self.assertEqual((0, 0, 0), budget.consumed)

    async def _consume_current_budget(self) -> RunBudget:
        budget = current_run_budget()
        assert budget is not None
        budget.consume_tokens(100)
        budget.consume_tool_call()
        budget.consume_delegation()
        return budget


class CancellationTest(unittest.IsolatedAsyncioTestCase):
    async def test_cancellation_checkpoint_raises_with_reason(self) -> None:
        token = CancellationToken()
        token.checkpoint()

        token.cancel("user_requested")

        self.assertTrue(token.is_cancelled)
        with self.assertRaisesRegex(RunCancelledError, "user_requested"):
            token.checkpoint()

    async def test_cancellation_scope_is_inherited_and_restored(self) -> None:
        self.assertIsNone(current_cancellation_token())

        outer = CancellationToken()
        with cancellation_scope(outer) as token:
            self.assertIs(outer, token)
            self.assertIs(token, current_cancellation_token())
            inherited = await asyncio.create_task(self._read_current_token())
            self.assertIs(token, inherited)
            cancellation_checkpoint()

            with cancellation_scope() as nested:
                self.assertIs(nested, current_cancellation_token())

            self.assertIs(token, current_cancellation_token())

        self.assertIsNone(current_cancellation_token())

    async def test_cancellable_await_returns_result(self) -> None:
        token = CancellationToken()

        result = await cancellable_await(asyncio.sleep(0, result="done"), token=token)

        self.assertEqual("done", result)

    async def test_cancellable_await_rejects_pre_cancelled_token_before_start(self) -> None:
        token = CancellationToken()
        started = False

        async def operation() -> None:
            nonlocal started
            started = True

        token.cancel("cancelled_before_start")

        with self.assertRaisesRegex(RunCancelledError, "cancelled_before_start"):
            await cancellable_await(operation(), token=token)
        self.assertFalse(started)

    async def test_cancellable_await_cancels_pending_operation(self) -> None:
        token = CancellationToken()
        started = asyncio.Event()
        cleaned_up = asyncio.Event()

        async def pending_operation() -> None:
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                cleaned_up.set()

        task = asyncio.create_task(cancellable_await(pending_operation(), token=token))
        await started.wait()

        token.cancel("run_cancelled")

        with self.assertRaisesRegex(RunCancelledError, "run_cancelled"):
            await task
        self.assertTrue(cleaned_up.is_set())

    async def test_cancellable_await_timeout_cleans_up_operation(self) -> None:
        cleaned_up = asyncio.Event()

        async def pending_operation() -> None:
            try:
                await asyncio.Event().wait()
            finally:
                cleaned_up.set()

        with self.assertRaises(TimeoutError):
            await cancellable_await(pending_operation(), timeout=0.01)
        self.assertTrue(cleaned_up.is_set())

    async def _read_current_token(self) -> CancellationToken | None:
        return current_cancellation_token()
