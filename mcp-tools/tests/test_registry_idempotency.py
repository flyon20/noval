from __future__ import annotations

import asyncio
import unittest

from fastapi import HTTPException
from pydantic import BaseModel

from app.registry import ToolDefinition, ToolRegistry


class WriteArgs(BaseModel):
    value: str


class FakeDurableRedis:
    def __init__(self) -> None:
        self.values: dict[str, str] = {}

    async def get(self, key: str):
        return self.values.get(key)

    async def set(self, key: str, value: str, *, nx: bool = False, ex: int | None = None):
        if nx and key in self.values:
            return False
        self.values[key] = value
        return True

    async def delete(self, key: str):
        self.values.pop(key, None)


class ToolRegistryIdempotencyTest(unittest.IsolatedAsyncioTestCase):
    def _registry(self, durable: FakeDurableRedis, handler) -> ToolRegistry:
        registry = ToolRegistry(idempotency_redis=durable)
        registry.register(ToolDefinition(
            name="test.write",
            description="test write",
            args_model=WriteArgs,
            handler=handler,
            routes=("market_scan",),
            side_effect_type="write",
            scope_requirement="project",
            timeout_ms=30000,
            identity_keys=("userId", "projectId"),
            secret_input_keys=(),
            secret_output_keys=(),
        ))
        return registry

    async def test_owner_cancellation_keeps_pending_write_joinable(self) -> None:
        registry = ToolRegistry(idempotency_redis=FakeDurableRedis())
        started = asyncio.Event()
        release = asyncio.Event()
        executions = 0

        async def handler(args: WriteArgs, _client) -> dict:
            nonlocal executions
            executions += 1
            started.set()
            await release.wait()
            return {"value": args.value}

        registry.register(ToolDefinition(
            name="test.write",
            description="test write",
            args_model=WriteArgs,
            handler=handler,
            routes=("market_scan",),
            side_effect_type="write",
            scope_requirement="project",
            timeout_ms=30000,
            identity_keys=("userId", "projectId"),
            secret_input_keys=(),
            secret_output_keys=(),
        ))
        arguments = {
            "value": "alpha",
            "userId": 7,
            "projectId": 9,
            "idempotencyKey": "write-once",
        }
        owner = asyncio.create_task(registry.call(
            name="test.write",
            arguments=arguments,
            backend_client=object(),
            route="market_scan",
        ))
        await started.wait()
        owner.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await owner

        joiner = asyncio.create_task(registry.call(
            name="test.write",
            arguments=arguments,
            backend_client=object(),
            route="market_scan",
        ))
        await asyncio.sleep(0)
        self.assertEqual(1, executions)
        release.set()

        result = await joiner
        self.assertEqual({"value": "alpha"}, result)
        self.assertEqual(1, executions)

    async def test_committed_write_survives_registry_restart(self) -> None:
        durable = FakeDurableRedis()
        executions = 0

        async def handler(args: WriteArgs, _client) -> dict:
            nonlocal executions
            executions += 1
            return {"value": args.value}

        def registry() -> ToolRegistry:
            instance = ToolRegistry(idempotency_redis=durable)
            instance.register(ToolDefinition(
                name="test.write",
                description="test write",
                args_model=WriteArgs,
                handler=handler,
                routes=("market_scan",),
                side_effect_type="write",
                scope_requirement="project",
                timeout_ms=30000,
                identity_keys=("userId", "projectId"),
                secret_input_keys=(),
                secret_output_keys=(),
            ))
            return instance

        arguments = {
            "value": "alpha",
            "userId": 7,
            "projectId": 9,
            "idempotencyKey": "write-once",
        }
        first = await registry().call(
            name="test.write",
            arguments=arguments,
            backend_client=object(),
            route="market_scan",
        )
        second = await registry().call(
            name="test.write",
            arguments=arguments,
            backend_client=object(),
            route="market_scan",
        )

        self.assertEqual({"value": "alpha"}, first)
        self.assertEqual(first, second)
        self.assertEqual(1, executions)

    async def test_pending_write_is_joined_across_registries_without_duplicate_handler(self) -> None:
        durable = FakeDurableRedis()
        started = asyncio.Event()
        release = asyncio.Event()
        executions = 0

        async def handler(args: WriteArgs, _client) -> dict:
            nonlocal executions
            executions += 1
            started.set()
            await release.wait()
            return {"value": args.value}

        owner_registry = self._registry(durable, handler)
        waiting_registry = self._registry(durable, handler)
        arguments = {
            "value": "alpha",
            "userId": 7,
            "projectId": 9,
            "idempotencyKey": "write-once",
        }
        owner = asyncio.create_task(owner_registry.call(
            name="test.write",
            arguments=arguments,
            backend_client=object(),
            route="market_scan",
        ))
        await started.wait()
        waiter = asyncio.create_task(waiting_registry.call(
            name="test.write",
            arguments=arguments,
            backend_client=object(),
            route="market_scan",
        ))
        await asyncio.sleep(0.02)

        self.assertFalse(waiter.done())
        self.assertEqual(1, executions)
        release.set()

        owner_result, waiter_result = await asyncio.gather(owner, waiter)
        self.assertEqual({"value": "alpha"}, owner_result)
        self.assertEqual(owner_result, waiter_result)
        self.assertEqual(1, executions)

    async def test_pending_write_wait_times_out_without_running_handler_again(self) -> None:
        durable = FakeDurableRedis()
        started = asyncio.Event()
        release = asyncio.Event()
        executions = 0

        async def handler(args: WriteArgs, _client) -> dict:
            nonlocal executions
            executions += 1
            started.set()
            await release.wait()
            return {"value": args.value}

        owner_registry = self._registry(durable, handler)
        waiting_registry = self._registry(durable, handler)
        waiting_registry._idempotency_wait_timeout_seconds = 0.03
        waiting_registry._idempotency_poll_interval_seconds = 0.005
        arguments = {
            "value": "alpha",
            "userId": 7,
            "projectId": 9,
            "idempotencyKey": "write-once",
        }
        owner = asyncio.create_task(owner_registry.call(
            name="test.write",
            arguments=arguments,
            backend_client=object(),
            route="market_scan",
        ))
        await started.wait()

        with self.assertRaises(HTTPException) as timeout:
            await waiting_registry.call(
                name="test.write",
                arguments=arguments,
                backend_client=object(),
                route="market_scan",
            )

        self.assertEqual(504, timeout.exception.status_code)
        self.assertEqual("idempotent write wait timed out", timeout.exception.detail)
        self.assertEqual(1, executions)
        release.set()
        await owner

    async def test_redis_unavailable_fails_closed_before_handler_execution(self) -> None:
        class UnavailableRedis:
            async def get(self, _key: str):
                raise ConnectionError("redis unavailable")

        executions = 0

        async def handler(args: WriteArgs, _client) -> dict:
            nonlocal executions
            executions += 1
            return {"value": args.value}

        registry = self._registry(UnavailableRedis(), handler)

        with self.assertRaises(HTTPException) as unavailable:
            await registry.call(
                name="test.write",
                arguments={
                    "value": "alpha",
                    "userId": 7,
                    "projectId": 9,
                    "idempotencyKey": "write-once",
                },
                backend_client=object(),
                route="market_scan",
            )

        self.assertEqual(503, unavailable.exception.status_code)
        self.assertEqual("durable MCP idempotency store is unavailable", unavailable.exception.detail)
        self.assertEqual(0, executions)


if __name__ == "__main__":
    unittest.main()
