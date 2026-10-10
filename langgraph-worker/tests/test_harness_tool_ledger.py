import asyncio
import unittest

from app.models.agent_task import RunToolIdentity
from app.services.harness.budget import RunBudget, run_budget_scope
from app.services.harness.cancellation import CancellationToken, cancellation_scope
from app.services.harness.tool_ledger import (
    RunToolLedger,
    current_run_tool_ledger,
    run_tool_ledger_scope,
)


class RunToolLedgerTest(unittest.IsolatedAsyncioTestCase):
    async def test_context_scope_is_inherited_and_restored(self) -> None:
        self.assertIsNone(current_run_tool_ledger())

        with run_tool_ledger_scope(self._ledger()) as ledger:
            self.assertIs(ledger, current_run_tool_ledger())
            inherited = await asyncio.create_task(self._current_ledger())
            self.assertIs(ledger, inherited)

            with run_tool_ledger_scope(ledger.for_route("book_breakdown")) as nested:
                self.assertIs(nested, current_run_tool_ledger())

            self.assertIs(ledger, current_run_tool_ledger())

        self.assertIsNone(current_run_tool_ledger())

    def test_normalizes_run_identity_and_isolates_call_ids(self) -> None:
        identity = RunToolIdentity(
            runId=" run-1 ",
            userId=7,
            projectId=" 91 ",
            route=" /Market_Scan/ ",
        )

        self.assertEqual("run-1", identity.runId)
        self.assertEqual("7", identity.userId)
        self.assertEqual("91", identity.projectId)
        self.assertEqual("market_scan", identity.route)

        arguments = {"limit": 3, "query": "trend"}
        call_id = RunToolLedger(identity).call_id("rank.lookup", arguments)
        reordered_call_id = RunToolLedger(identity).call_id(
            "rank.lookup",
            {"query": "trend", "limit": 3},
        )
        self.assertEqual(call_id, reordered_call_id)

        for changed_identity in (
            RunToolIdentity(runId="run-1", userId=8, projectId=91, route="market_scan"),
            RunToolIdentity(runId="run-1", userId=7, projectId=92, route="market_scan"),
        ):
            with self.subTest(identity=changed_identity):
                self.assertNotEqual(
                    call_id,
                    RunToolLedger(changed_identity).call_id("rank.lookup", arguments),
                )
        self.assertEqual(
            call_id,
            RunToolLedger(RunToolIdentity(
                runId="run-1",
                userId=7,
                projectId=91,
                route="book_breakdown",
            )).call_id("rank.lookup", arguments),
        )

    def test_default_reuse_key_preserves_the_canonical_identity(self) -> None:
        ledger = self._ledger()
        canonical_fingerprint = "sha256:canonical"
        expected = "tool_reuse_" + ledger._digest({
            "scope": ledger.identity.dedupe_scope_key,
            "tool": "knowledge.search",
            "fingerprint": canonical_fingerprint,
        })

        self.assertEqual(
            expected,
            ledger._reuse_key(
                " Knowledge.Search ",
                canonical_fingerprint,
                None,
                True,
            ),
        )

    async def test_route_views_share_one_run_level_read_result(self) -> None:
        ledger = self._ledger()
        executions = 0

        async def operation() -> dict:
            nonlocal executions
            executions += 1
            return {"items": [1]}

        market = await ledger.execute(
            "rank.lookup",
            {"channelCode": "male-new"},
            operation,
            route="market_scan",
        )
        author = await ledger.execute(
            "rank.lookup",
            {"channel_code": "male-new"},
            operation,
            route="mixed_creation_research",
        )

        self.assertEqual(1, executions)
        self.assertFalse(market.reused)
        self.assertTrue(author.reused)
        self.assertEqual("mixed_creation_research", author.route)

    async def test_concurrent_read_calls_join_one_pending_execution(self) -> None:
        ledger = self._ledger()
        started = asyncio.Event()
        release = asyncio.Event()
        executions = 0

        async def operation() -> dict:
            nonlocal executions
            executions += 1
            started.set()
            await release.wait()
            return {"items": [1, 2, 3]}

        first = asyncio.create_task(
            ledger.execute("rank.lookup", {"query": "trend"}, operation)
        )
        await started.wait()
        second = asyncio.create_task(
            ledger.execute("rank.lookup", {"query": "trend"}, operation)
        )
        await asyncio.sleep(0)
        release.set()

        first_run, second_run = await asyncio.gather(first, second)

        self.assertEqual(1, executions)
        self.assertEqual("succeeded", first_run.status)
        self.assertEqual(first_run.callId, second_run.callId)
        self.assertTrue(second_run.joined)
        self.assertTrue(second_run.reused)

    async def test_cancelled_joiner_stops_waiting_without_cancelling_shared_execution(self) -> None:
        ledger = self._ledger()
        started = asyncio.Event()
        release = asyncio.Event()

        async def operation() -> dict:
            started.set()
            await release.wait()
            return {"ok": True}

        first = asyncio.create_task(ledger.execute("rank.lookup", {"query": "trend"}, operation))
        await started.wait()
        joiner_token = CancellationToken()
        second = asyncio.create_task(ledger.execute(
            "rank.lookup",
            {"query": "trend"},
            operation,
            cancellation_token=joiner_token,
        ))
        await asyncio.sleep(0)
        joiner_token.cancel("joiner_cancelled")

        cancelled = await second
        self.assertEqual("cancelled", cancelled.status)
        self.assertTrue(cancelled.joined)
        self.assertFalse(first.done())

        release.set()
        completed = await first
        self.assertEqual("succeeded", completed.status)

    async def test_cancelled_only_waiter_waits_for_underlying_tool_cleanup(self) -> None:
        ledger = self._ledger()
        started = asyncio.Event()
        cleaned_up = asyncio.Event()

        async def operation() -> dict:
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                cleaned_up.set()

        execution = asyncio.create_task(
            ledger.execute("rank.lookup", {"query": "trend"}, operation)
        )
        await started.wait()

        execution.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await execution

        self.assertTrue(cleaned_up.is_set())

    async def test_cancelled_original_waiter_keeps_execution_for_active_joiner(self) -> None:
        ledger = self._ledger()
        started = asyncio.Event()
        release = asyncio.Event()

        async def operation() -> dict:
            started.set()
            await release.wait()
            return {"ok": True}

        original = asyncio.create_task(
            ledger.execute("rank.lookup", {"query": "trend"}, operation)
        )
        await started.wait()
        joiner = asyncio.create_task(
            ledger.execute("rank.lookup", {"query": "trend"}, operation)
        )
        await asyncio.sleep(0)

        original.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await original
        self.assertFalse(joiner.done())

        release.set()
        completed = await joiner
        self.assertEqual("succeeded", completed.status)
        self.assertTrue(completed.joined)

    async def test_original_waiter_token_cannot_cancel_active_joiner_execution(self) -> None:
        ledger = self._ledger()
        started = asyncio.Event()
        release = asyncio.Event()
        original_token = CancellationToken()

        async def operation() -> dict:
            started.set()
            await release.wait()
            return {"ok": True}

        original = asyncio.create_task(ledger.execute(
            "rank.lookup",
            {"query": "trend"},
            operation,
            cancellation_token=original_token,
        ))
        await started.wait()
        joiner = asyncio.create_task(
            ledger.execute("rank.lookup", {"query": "trend"}, operation)
        )
        await asyncio.sleep(0)

        original_token.cancel("original_waiter_cancelled")
        cancelled = await original
        self.assertEqual("cancelled", cancelled.status)
        self.assertFalse(joiner.done())

        release.set()
        completed = await joiner
        self.assertEqual("succeeded", completed.status)
        self.assertTrue(completed.joined)

    async def test_completed_reads_are_reused_without_spending_tool_budget(self) -> None:
        ledger = self._ledger()
        budget = RunBudget.fast()
        executions = 0

        async def operation() -> dict:
            nonlocal executions
            executions += 1
            return {"value": executions}

        with run_budget_scope(budget):
            first = await ledger.execute("knowledge.search", {"query": "arc"}, operation)
            second = await ledger.execute("knowledge.search", {"query": "arc"}, operation)

        self.assertEqual(1, executions)
        self.assertEqual(1, budget.used_tool_calls)
        self.assertEqual(first.callId, second.callId)
        self.assertEqual(first.output, second.output)
        self.assertTrue(second.reused)
        self.assertFalse(second.executed)

    async def test_question_is_part_of_semantic_tool_identity(self) -> None:
        ledger = self._ledger()
        executions = 0

        async def operation() -> dict:
            nonlocal executions
            executions += 1
            return {"execution": executions}

        first = await ledger.execute(
            "reader.simulate_feedback",
            {"question": "Does the opening hook work?"},
            operation,
        )
        second = await ledger.execute(
            "reader.simulate_feedback",
            {"question": "Is the protagonist motivation clear?"},
            operation,
        )

        self.assertEqual(2, executions)
        self.assertEqual({"execution": 1}, first.output)
        self.assertEqual({"execution": 2}, second.output)
        self.assertFalse(second.reused)

    async def test_failed_read_is_not_cached_and_can_retry(self) -> None:
        ledger = self._ledger()
        budget = RunBudget.fast()
        executions = 0

        async def operation() -> dict:
            nonlocal executions
            executions += 1
            if executions == 1:
                raise ConnectionError("temporary failure")
            return {"value": "recovered"}

        with run_budget_scope(budget):
            failed = await ledger.execute("knowledge.search", {"query": "arc"}, operation)
            retried = await ledger.execute("knowledge.search", {"query": "arc"}, operation)

        self.assertEqual(2, executions)
        self.assertEqual(2, budget.used_tool_calls)
        self.assertEqual("failed", failed.status)
        self.assertEqual("succeeded", retried.status)
        self.assertFalse(retried.reused)

    async def test_writes_only_reuse_with_an_idempotency_key(self) -> None:
        ledger = self._ledger()
        budget = RunBudget.fast()
        executions = 0

        async def operation() -> dict:
            nonlocal executions
            executions += 1
            return {"version": executions}

        with run_budget_scope(budget):
            first = await ledger.execute(
                "memory.write",
                {"content": "alpha"},
                operation,
                access="write",
            )
            second = await ledger.execute(
                "memory.write",
                {"content": "alpha"},
                operation,
                access="write",
            )
            keyed_first = await ledger.execute(
                "memory.write",
                {"content": "beta"},
                operation,
                access="write",
                idempotency_key=" save-beta ",
            )
            keyed_second = await ledger.execute(
                "memory.write",
                {"content": "beta"},
                operation,
                access="write",
                idempotency_key="save-beta",
            )

        self.assertEqual(3, executions)
        self.assertEqual(3, budget.used_tool_calls)
        self.assertNotEqual(first.callId, second.callId)
        self.assertEqual(keyed_first.callId, keyed_second.callId)
        self.assertEqual(keyed_first.idempotencyId, keyed_second.idempotencyId)
        self.assertTrue(keyed_second.reused)

    async def test_write_can_invalidate_prior_read_results_before_retry(self) -> None:
        ledger = self._ledger()
        values = iter(([{"rank": 1, "snapshot": "old"}], [{"rank": 1, "snapshot": "fresh"}]))

        first = await ledger.execute(
            "rank.lookup",
            {"boardCode": "urban-brain"},
            lambda: next(values),
        )
        async def refresh() -> dict:
            return {"snapshotId": 2}

        await ledger.execute(
            "rank.refresh",
            {"boardCode": "urban-brain", "idempotencyKey": "refresh-1"},
            refresh,
            access="write",
            idempotency_key="refresh-1",
        )
        await ledger.invalidate("rank.lookup")
        second = await ledger.execute(
            "rank.lookup",
            {"boardCode": "urban-brain"},
            lambda: next(values),
        )

        self.assertEqual("old", first.output["items"][0]["snapshot"])
        self.assertEqual("fresh", second.output["items"][0]["snapshot"])
        self.assertTrue(first.executed)
        self.assertTrue(second.executed)

    async def test_invalidation_does_not_let_an_old_pending_read_replace_fresh_result(self) -> None:
        ledger = self._ledger()
        old_started = asyncio.Event()
        release_old = asyncio.Event()

        async def old_lookup() -> list[dict]:
            old_started.set()
            await release_old.wait()
            return [{"snapshot": "old"}]

        old_task = asyncio.create_task(ledger.execute(
            "rank.lookup",
            {"boardCode": "urban-brain"},
            old_lookup,
        ))
        await old_started.wait()
        await ledger.invalidate("rank.lookup")
        fresh = await ledger.execute(
            "rank.lookup",
            {"boardCode": "urban-brain"},
            lambda: [{"snapshot": "fresh"}],
        )
        release_old.set()
        invalidated = await old_task
        reused = await ledger.execute(
            "rank.lookup",
            {"boardCode": "urban-brain"},
            lambda: [{"snapshot": "unexpected"}],
        )

        self.assertEqual("fresh", fresh.output["items"][0]["snapshot"])
        self.assertEqual("invalidated", invalidated.status)
        self.assertEqual("fresh", reused.output["items"][0]["snapshot"])
        self.assertTrue(reused.reused)

    async def test_synchronous_blocking_tool_is_timed_out_off_the_event_loop(self) -> None:
        import time

        ledger = self._ledger()
        heartbeat = asyncio.Event()

        async def prove_event_loop_is_alive() -> None:
            await asyncio.sleep(0.01)
            heartbeat.set()

        heartbeat_task = asyncio.create_task(prove_event_loop_is_alive())
        run = await ledger.execute(
            "legacy.sync_tool",
            {},
            lambda: time.sleep(0.2),
            timeout=0.02,
        )
        await heartbeat_task

        self.assertTrue(heartbeat.is_set())
        self.assertEqual("timed_out", run.status)

    async def test_rejects_same_idempotency_key_with_different_arguments(self) -> None:
        ledger = self._ledger()
        executions = 0

        async def operation() -> dict:
            nonlocal executions
            executions += 1
            return {"version": executions}

        first = await ledger.execute(
            "memory.write",
            {"content": "alpha"},
            operation,
            access="write",
            idempotency_key="save-one",
        )
        conflict = await ledger.execute(
            "memory.write",
            {"content": "beta"},
            operation,
            access="write",
            idempotency_key="save-one",
        )

        self.assertEqual("succeeded", first.status)
        self.assertEqual("failed", conflict.status)
        self.assertEqual("IdempotencyConflict", conflict.errorType)
        self.assertFalse(conflict.executed)
        self.assertEqual(1, executions)

    async def test_rejects_same_supplied_call_id_with_different_arguments(self) -> None:
        ledger = self._ledger()
        first = await ledger.execute(
            "rank.lookup",
            {"query": "alpha"},
            lambda: {"value": "alpha"},
            call_id="provider-call-1",
        )
        conflict = await ledger.execute(
            "rank.lookup",
            {"query": "beta"},
            lambda: {"value": "beta"},
            call_id="provider-call-1",
        )

        self.assertEqual("succeeded", first.status)
        self.assertEqual("failed", conflict.status)
        self.assertEqual("CallIdentityConflict", conflict.errorType)
        self.assertFalse(conflict.executed)

    async def test_route_children_share_run_level_cache_and_records(self) -> None:
        root = RunToolLedger(
            RunToolIdentity(
                runId="run-1",
                userId=7,
                projectId=91,
                route="agent_run",
            )
        )
        market = root.for_route("market_scan")
        book = root.for_route("book_breakdown")
        executions = 0

        async def operation() -> dict:
            nonlocal executions
            executions += 1
            return {"execution": executions}

        market_first = await market.execute(
            "knowledge.search",
            {"query": "same"},
            operation,
            call_id="provider-call-1",
        )
        book_first = await book.execute(
            "knowledge.search",
            {"query": "same"},
            operation,
            call_id="provider-call-1",
        )
        market_reused = await root.execute(
            "knowledge.search",
            {"query": "same"},
            operation,
            call_id="provider-call-1",
            route="market_scan",
        )

        self.assertEqual(1, executions)
        self.assertEqual("provider-call-1", market_first.callId)
        self.assertEqual("provider-call-1", book_first.callId)
        self.assertTrue(book_first.reused)
        self.assertEqual("book_breakdown", book_first.route)
        self.assertEqual(market_first.callId, market_reused.callId)
        self.assertTrue(market_reused.reused)
        self.assertEqual(3, len(root.runs))
        self.assertEqual(root.runs, market.runs)
        self.assertEqual(root.runs, book.runs)

    async def test_project_scope_view_preserves_user_budget_and_shared_records(self) -> None:
        budget = RunBudget.fast()
        root = RunToolLedger(
            RunToolIdentity(
                runId="run-project-reference",
                userId=7,
                projectId=91,
                route="project_knowledge",
            ),
            budget=budget,
        )
        reference = root.for_project_scope(92)

        run = await reference.execute(
            "project.retrieve",
            {"projectId": 92, "workId": 921},
            lambda: {"evidence": []},
        )

        self.assertEqual("succeeded", run.status)
        self.assertEqual("run-project-reference", run.runId)
        self.assertEqual("7", run.userId)
        self.assertEqual("92", run.projectId)
        self.assertEqual(1, budget.used_tool_calls)
        self.assertEqual(root.runs, reference.runs)

    async def test_same_route_children_join_shared_pending_execution(self) -> None:
        root = RunToolLedger(
            RunToolIdentity(
                runId="run-1",
                userId=7,
                projectId=91,
                route="agent_run",
            )
        )
        first_view = root.for_route("market_scan")
        second_view = root.for_route("market_scan")
        started = asyncio.Event()
        release = asyncio.Event()
        executions = 0

        async def operation() -> dict:
            nonlocal executions
            executions += 1
            started.set()
            await release.wait()
            return {"ok": True}

        first = asyncio.create_task(
            first_view.execute("rank.lookup", {"query": "trend"}, operation)
        )
        await started.wait()
        second = asyncio.create_task(
            second_view.execute("rank.lookup", {"query": "trend"}, operation)
        )
        await asyncio.sleep(0)
        release.set()
        first_run, second_run = await asyncio.gather(first, second)

        self.assertEqual(1, executions)
        self.assertEqual(first_run.callId, second_run.callId)
        self.assertTrue(second_run.joined)

    async def test_records_failed_timed_out_and_cancelled_terminal_states(self) -> None:
        ledger = self._ledger()

        async def fail() -> None:
            raise ValueError("bad input")

        failed = await ledger.execute("tool.fail", {}, fail)
        self.assertEqual("failed", failed.status)
        self.assertEqual("ValueError", failed.errorType)

        timed_out = await ledger.execute(
            "tool.slow",
            {},
            lambda: asyncio.Event().wait(),
            timeout=0.01,
        )
        self.assertEqual("timed_out", timed_out.status)
        self.assertEqual("ToolTimeout", timed_out.errorType)

        token = CancellationToken()
        started = asyncio.Event()

        async def wait_forever() -> None:
            started.set()
            await asyncio.Event().wait()

        with cancellation_scope(token):
            pending = asyncio.create_task(
                ledger.execute("tool.cancel", {}, wait_forever)
            )
            await started.wait()
            token.cancel("user_requested")
            cancelled = await pending

        self.assertEqual("cancelled", cancelled.status)
        self.assertEqual("RunCancelledError", cancelled.errorType)

    async def test_pre_cancelled_token_does_not_dispatch_or_prepare_tool(self) -> None:
        token = CancellationToken()
        token.cancel("cancelled_before_dispatch")
        checkpoints: list[tuple[str, str, dict]] = []
        executed = False

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            checkpoints.append((event_type, event_key, payload))

        async def operation() -> dict:
            nonlocal executed
            executed = True
            return {"mustNot": "run"}

        ledger = RunToolLedger(self._ledger().identity, checkpoint_writer=writer)
        cancelled = await ledger.execute(
            "rank.lookup",
            {"query": "trend"},
            operation,
            cancellation_token=token,
        )

        self.assertEqual("cancelled", cancelled.status)
        self.assertEqual("RunCancelledError", cancelled.errorType)
        self.assertFalse(cancelled.executed)
        self.assertFalse(executed)
        self.assertEqual([], checkpoints)

    async def test_cancelled_tool_is_quiescent_before_structured_terminal_returns(self) -> None:
        token = CancellationToken()
        started = asyncio.Event()
        cleaned_up = asyncio.Event()
        checkpoints: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            checkpoints.append((event_type, event_key, payload))

        async def operation() -> None:
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                cleaned_up.set()

        ledger = RunToolLedger(self._ledger().identity, checkpoint_writer=writer)
        pending = asyncio.create_task(ledger.execute(
            "rank.lookup",
            {"query": "trend"},
            operation,
            timeout=1.0,
            cancellation_token=token,
        ))
        await started.wait()

        token.cancel("upstream_cancelled_before_timeout")
        cancelled = await pending

        self.assertTrue(cleaned_up.is_set())
        self.assertEqual("cancelled", cancelled.status)
        self.assertEqual("RunCancelledError", cancelled.errorType)
        self.assertIn("upstream_cancelled_before_timeout", cancelled.output["message"])
        self.assertEqual(
            ["TOOL_PREPARED", "TOOL_COMMITTED"],
            [event[0] for event in checkpoints],
        )
        self.assertEqual("cancelled", checkpoints[-1][2]["run"]["status"])
        self.assertEqual("RunCancelledError", checkpoints[-1][2]["run"]["errorType"])

    async def test_redacts_nested_inputs_and_outputs(self) -> None:
        ledger = self._ledger()

        run = await ledger.execute(
            "tool.secret",
            {
                "authorization": "Bearer input-secret",
                "nested": [{"apiKey": "input-key"}],
                "visible": "ok",
            },
            lambda: {
                "token": "output-token",
                "nested": {"password": "output-password"},
                "visible": "result",
            },
        )

        self.assertEqual("[redacted]", run.input["authorization"])
        self.assertEqual("[redacted]", run.input["nested"][0]["apiKey"])
        self.assertEqual("ok", run.input["visible"])
        self.assertEqual("[redacted]", run.output["token"])
        self.assertEqual("[redacted]", run.output["nested"]["password"])
        self.assertEqual("result", run.output["visible"])

    async def test_redacts_manifest_declared_custom_secret_keys(self) -> None:
        run = await self._ledger().execute(
            "tool.custom_secret",
            {"credentialBlob": "input-secret", "visible": "ok"},
            lambda: {"privatePayload": "output-secret", "visible": "result"},
            secret_input_keys={"credentialBlob"},
            secret_output_keys={"privatePayload"},
        )

        self.assertEqual("[redacted]", run.input["credentialBlob"])
        self.assertEqual("[redacted]", run.output["privatePayload"])
        self.assertEqual("ok", run.input["visible"])
        self.assertEqual("result", run.output["visible"])

    async def test_redacts_manifest_secrets_from_exception_text(self) -> None:
        def fail() -> None:
            raise ValueError('{"credentialBlob":"exception-secret"}')

        run = await self._ledger().execute(
            "tool.custom_secret_error",
            {"visible": "ok"},
            fail,
            secret_input_keys={"credentialBlob"},
        )

        self.assertEqual("failed", run.status)
        self.assertNotIn("exception-secret", run.output["message"])
        self.assertIn("[redacted]", run.output["message"])

    async def test_redacts_default_secret_with_spaces_from_json_exception_text(self) -> None:
        def fail() -> None:
            raise ValueError('{"apiKey":"secret value"}')

        run = await self._ledger().execute("tool.default_secret_error", {}, fail)

        self.assertEqual("failed", run.status)
        self.assertNotIn("secret value", run.output["message"])
        self.assertIn('[redacted]', run.output["message"])

    async def test_redacts_custom_secret_with_spaces_from_json_exception_text(self) -> None:
        def fail() -> None:
            raise ValueError('{"credentialBlob":"custom secret value"}')

        run = await self._ledger().execute(
            "tool.custom_secret_with_spaces_error",
            {},
            fail,
            secret_output_keys={"credentialBlob"},
        )

        self.assertEqual("failed", run.status)
        self.assertNotIn("custom secret value", run.output["message"])
        self.assertIn('[redacted]', run.output["message"])

    async def test_redacts_quoted_secret_with_spaces_inside_non_json_exception_text(self) -> None:
        def fail() -> None:
            raise ValueError('provider rejected apiKey="secret value" during request')

        run = await self._ledger().execute("tool.quoted_secret_error", {}, fail)

        self.assertEqual("failed", run.status)
        self.assertNotIn("secret value", run.output["message"])
        self.assertIn('apiKey="[redacted]"', run.output["message"])

    async def test_redacts_quoted_secret_when_key_name_contains_spaces(self) -> None:
        def fail() -> None:
            raise ValueError('provider rejected "api key" = "secret value"')

        run = await self._ledger().execute("tool.spaced_secret_key_error", {}, fail)

        self.assertEqual("failed", run.status)
        self.assertNotIn("secret value", run.output["message"])
        self.assertIn('"api key" = "[redacted]"', run.output["message"])

    async def test_rejects_synchronous_write_handlers_before_side_effects(self) -> None:
        executed = False

        def write() -> dict:
            nonlocal executed
            executed = True
            return {"saved": True}

        run = await self._ledger().execute(
            "memory.write",
            {"content": "value"},
            write,
            access="write",
            idempotency_key="write-1",
        )

        self.assertEqual("failed", run.status)
        self.assertEqual("SyncWriteToolRejected", run.errorType)
        self.assertFalse(executed)

    async def test_persists_prepared_before_dispatch_and_committed_after_result(self) -> None:
        events: list[tuple[str, str, dict]] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            events.append((event_type, event_key, payload))

        ledger = RunToolLedger(self._ledger().identity, checkpoint_writer=writer)

        async def operation() -> dict:
            self.assertEqual("TOOL_PREPARED", events[-1][0])
            return {"saved": True}

        run = await ledger.execute(
            "rank.refresh",
            {"boardCode": "urban-brain"},
            operation,
            access="write",
            idempotency_key="refresh-once",
        )

        self.assertEqual("succeeded", run.status)
        self.assertEqual(["TOOL_PREPARED", "TOOL_COMMITTED"], [event[0] for event in events])
        self.assertNotIn("idempotencyKey", str(events))
        self.assertEqual("succeeded", events[-1][2]["run"]["status"])

    async def test_prepared_checkpoint_failure_is_fail_closed(self) -> None:
        executions = 0

        async def writer(_event_type: str, _event_key: str, _payload: dict) -> None:
            raise RuntimeError("checkpoint unavailable")

        async def operation() -> dict:
            nonlocal executions
            executions += 1
            return {"saved": True}

        ledger = RunToolLedger(self._ledger().identity, checkpoint_writer=writer)
        with self.assertRaisesRegex(RuntimeError, "checkpoint unavailable"):
            await ledger.execute("rank.refresh", {}, operation, access="write", idempotency_key="once")

        self.assertEqual(0, executions)

    async def test_uncommitted_prepared_event_recovers_as_unknown_without_replay(self) -> None:
        persisted: list[tuple[str, str, dict]] = []

        async def interrupted_writer(event_type: str, event_key: str, payload: dict) -> None:
            persisted.append((event_type, event_key, payload))
            if event_type == "TOOL_COMMITTED":
                raise RuntimeError("commit interrupted")

        async def completed_operation() -> dict:
            return {"saved": True}

        first = RunToolLedger(self._ledger().identity, checkpoint_writer=interrupted_writer)
        with self.assertRaisesRegex(RuntimeError, "commit interrupted"):
            await first.execute(
                "rank.refresh",
                {"boardCode": "urban-brain"},
                completed_operation,
                access="write",
                idempotency_key="refresh-once",
            )

        prepared = persisted[0]
        resumed = RunToolLedger(self._ledger().identity)
        unknown_payloads = resumed.merge_semantic_events([{
            "sequenceNo": 1,
            "eventType": prepared[0],
            "eventIdempotencyKey": prepared[1],
            "payload": prepared[2],
        }])
        executions = 0

        async def must_not_run() -> dict:
            nonlocal executions
            executions += 1
            return {"saved": True}

        recovered = await resumed.execute(
            "rank.refresh",
            {"board_code": "urban-brain"},
            must_not_run,
            access="write",
            idempotency_key="refresh-once",
        )

        self.assertEqual(1, len(unknown_payloads))
        self.assertEqual("unknown", recovered.status)
        self.assertEqual("ToolOutcomeUnknown", recovered.errorType)
        self.assertFalse(recovered.executed)
        self.assertEqual(0, executions)

    async def test_unknown_recovery_uses_stable_reuse_key_when_call_id_changes(self) -> None:
        persisted: list[dict] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            if event_type == "TOOL_PREPARED":
                persisted.append({
                    "sequenceNo": 1,
                    "eventType": event_type,
                    "eventIdempotencyKey": event_key,
                    "payload": payload,
                })

        first = RunToolLedger(self._ledger().identity, checkpoint_writer=writer)
        await first.execute(
            "rank.lookup",
            {"boardCode": "urban-brain"},
            lambda: {"items": []},
        )

        resumed = RunToolLedger(self._ledger().identity)
        recovered_unknowns = resumed.merge_semantic_events(persisted)
        self.assertEqual(1, len(recovered_unknowns))
        executions = 0

        async def must_not_run() -> dict:
            nonlocal executions
            executions += 1
            return {"items": [{"mustNot": "execute"}]}

        recovered = await resumed.execute(
            "rank.lookup",
            {"board_code": "urban-brain"},
            must_not_run,
            call_id="model-generated-new-call-id",
        )

        self.assertEqual("unknown", recovered.status)
        self.assertEqual("ToolOutcomeUnknown", recovered.errorType)
        self.assertTrue(recovered.reused)
        self.assertEqual(0, executions)

    async def test_semantic_invalidation_generation_prevents_stale_recovery(self) -> None:
        persisted: list[dict] = []

        async def writer(event_type: str, event_key: str, payload: dict) -> None:
            persisted.append({
                "sequenceNo": len(persisted) + 1,
                "eventType": event_type,
                "eventIdempotencyKey": event_key,
                "payload": payload,
            })

        ledger = RunToolLedger(self._ledger().identity, checkpoint_writer=writer)
        first = await ledger.execute(
            "rank.lookup",
            {"boardCode": "urban-brain"},
            lambda: {"items": [{"snapshot": "old"}]},
        )
        await ledger.invalidate("rank.lookup")
        second = await ledger.execute(
            "rank.lookup",
            {"boardCode": "urban-brain"},
            lambda: {"items": [{"snapshot": "fresh"}]},
        )

        resumed = RunToolLedger(self._ledger().identity)
        self.assertEqual([], resumed.merge_semantic_events(persisted))
        restored = await resumed.execute(
            "rank.lookup",
            {"boardCode": "urban-brain"},
            lambda: {"items": [{"snapshot": "must-not-run"}]},
        )

        self.assertEqual("old", first.output["items"][0]["snapshot"])
        self.assertEqual("fresh", second.output["items"][0]["snapshot"])
        self.assertEqual("fresh", restored.output["items"][0]["snapshot"])
        self.assertTrue(restored.reused)
        self.assertEqual(
            ["TOOL_PREPARED", "TOOL_COMMITTED", "TOOL_INVALIDATED", "TOOL_PREPARED", "TOOL_COMMITTED"],
            [event["eventType"] for event in persisted],
        )

    async def test_checkpoint_restores_committed_idempotent_result(self) -> None:
        ledger = self._ledger()
        executions = 0

        async def operation() -> dict:
            nonlocal executions
            executions += 1
            return {"version": executions}

        first = await ledger.execute(
            "rank.refresh",
            {"boardCode": "urban-brain"},
            operation,
            access="write",
            idempotency_key="refresh-once",
        )
        resumed = self._ledger()
        resumed.merge_checkpoint(ledger.checkpoint_snapshot())
        second = await resumed.execute(
            "rank.refresh",
            {"board_code": "urban-brain"},
            operation,
            access="write",
            idempotency_key="refresh-once",
        )

        self.assertEqual("succeeded", first.status)
        self.assertTrue(second.reused)
        self.assertFalse(second.executed)
        self.assertEqual(1, executions)

    async def test_checkpoint_restores_successful_read_without_reexecution_or_recharge(self) -> None:
        first_budget = RunBudget.fast()
        ledger = RunToolLedger(self._ledger().identity, budget=first_budget)
        executions = 0

        async def operation() -> dict:
            nonlocal executions
            executions += 1
            return {"items": [{"rankNo": 1}]}

        first = await ledger.execute("rank.lookup", {"boardCode": "urban-brain"}, operation)
        resumed_budget = RunBudget.fast()
        resumed = RunToolLedger(self._ledger().identity, budget=resumed_budget)
        resumed.merge_checkpoint(ledger.checkpoint_snapshot())
        second = await resumed.execute(
            "rank.lookup",
            {"board_code": "urban-brain"},
            operation,
            route="mixed_creation_research",
        )

        self.assertEqual("succeeded", first.status)
        self.assertEqual(1, first_budget.used_tool_calls)
        self.assertTrue(second.reused)
        self.assertFalse(second.executed)
        self.assertEqual(0, resumed_budget.used_tool_calls)
        self.assertEqual(1, executions)

    async def test_external_side_effect_fence_is_conservative_and_ignores_reads(self) -> None:
        ledger = self._ledger()
        await ledger.execute("rank.lookup", {}, lambda: {"items": []})
        self.assertFalse(ledger.has_external_side_effect())

        started_write = await ledger.execute(
            "memory.write",
            {},
            self._async_failure,
            access="write",
        )
        self.assertTrue(started_write.executed)
        self.assertTrue(ledger.has_external_side_effect())

        prepared = {
            "schemaVersion": "run-tool-ledger-v2",
            "runId": ledger.identity.runId,
            "userId": ledger.identity.userId,
            "projectId": ledger.identity.projectId,
            "completed": [],
            "semanticTerminals": [{
                "semanticKey": "tool_semantic_unknown_write",
                "run": {
                    "name": "rank.refresh",
                    "status": "unknown",
                    "access": "idempotent",
                    "executed": False,
                },
            }],
        }
        resumed = self._ledger()
        resumed.merge_checkpoint(prepared)
        self.assertTrue(resumed.has_external_side_effect())

    @staticmethod
    async def _async_failure() -> None:
        raise RuntimeError("write may have partially completed")

    def _ledger(self) -> RunToolLedger:
        return RunToolLedger(
            RunToolIdentity(
                runId="run-1",
                userId=7,
                projectId=91,
                route="market_scan",
            )
        )

    async def _current_ledger(self) -> RunToolLedger | None:
        return current_run_tool_ledger()


if __name__ == "__main__":
    unittest.main()
