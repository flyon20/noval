from __future__ import annotations

import asyncio
import inspect
from collections.abc import Awaitable, Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from typing import TypeVar


T = TypeVar("T")


class RunCancelledError(asyncio.CancelledError):
    """Raised at a harness checkpoint after a run cancellation request."""


class CancellationToken:
    def __init__(self) -> None:
        self._event = asyncio.Event()
        self._reason: str | None = None

    @property
    def is_cancelled(self) -> bool:
        return self._event.is_set()

    @property
    def reason(self) -> str | None:
        return self._reason

    def cancel(self, reason: str | None = None) -> bool:
        if self.is_cancelled:
            return False
        self._reason = str(reason or "run_cancelled")
        self._event.set()
        return True

    def checkpoint(self) -> None:
        if self.is_cancelled:
            raise RunCancelledError(self._reason or "run_cancelled")

    def throw_if_cancelled(self) -> None:
        self.checkpoint()

    async def wait(self) -> str:
        await self._event.wait()
        return self._reason or "run_cancelled"


_CURRENT_CANCELLATION_TOKEN: ContextVar[CancellationToken | None] = ContextVar(
    "harness_cancellation_token",
    default=None,
)


def current_cancellation_token() -> CancellationToken | None:
    return _CURRENT_CANCELLATION_TOKEN.get()


@contextmanager
def cancellation_scope(token: CancellationToken | None = None) -> Iterator[CancellationToken]:
    scoped_token = token or CancellationToken()
    reset_token = _CURRENT_CANCELLATION_TOKEN.set(scoped_token)
    try:
        yield scoped_token
    finally:
        try:
            _CURRENT_CANCELLATION_TOKEN.reset(reset_token)
        except ValueError:
            pass


def cancellation_checkpoint(token: CancellationToken | None = None) -> None:
    active_token = token or current_cancellation_token()
    if active_token is not None:
        active_token.checkpoint()


async def cancellable_await(
    awaitable: Awaitable[T],
    *,
    token: CancellationToken | None = None,
    timeout: float | None = None,
) -> T:
    active_token = token or current_cancellation_token()
    if active_token is None:
        if timeout is None:
            return await awaitable
        return await asyncio.wait_for(awaitable, timeout=timeout)
    if active_token is not None and active_token.is_cancelled:
        _dispose_unstarted_awaitable(awaitable)
        active_token.checkpoint()

    operation = asyncio.ensure_future(awaitable)
    try:
        await asyncio.sleep(0)
    except asyncio.CancelledError:
        await _cancel_and_wait(operation)
        raise
    if active_token.is_cancelled:
        await _cancel_and_wait(operation)
        active_token.checkpoint()
    if operation.done():
        result = await operation
        active_token.checkpoint()
        return result

    cancellation_waiter = (
        asyncio.create_task(active_token.wait())
        if active_token is not None
        else None
    )
    waiters = {operation}
    if cancellation_waiter is not None:
        waiters.add(cancellation_waiter)

    try:
        done, _ = await asyncio.wait(
            waiters,
            timeout=timeout,
            return_when=asyncio.FIRST_COMPLETED,
        )
        if not done:
            await _cancel_and_wait(operation)
            raise TimeoutError("cancellable await timed out")
        if cancellation_waiter is not None and cancellation_waiter in done:
            await _cancel_and_wait(operation)
            active_token.checkpoint()

        result = await operation
        if active_token is not None:
            active_token.checkpoint()
        return result
    except asyncio.CancelledError:
        await _cancel_and_wait(operation)
        raise
    finally:
        if cancellation_waiter is not None:
            await _cancel_and_wait(cancellation_waiter)


async def _cancel_and_wait(task: asyncio.Future[object]) -> None:
    if not task.done():
        task.cancel()
    await asyncio.gather(task, return_exceptions=True)


def _dispose_unstarted_awaitable(awaitable: Awaitable[object]) -> None:
    if isinstance(awaitable, asyncio.Future):
        awaitable.cancel()
    elif inspect.iscoroutine(awaitable):
        awaitable.close()
