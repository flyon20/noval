from __future__ import annotations

import asyncio
import threading
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from contextvars import ContextVar

from app.config import settings
from app.services.harness.cancellation import CancellationToken, cancellable_await


ADMISSION_POLL_INTERVAL_SECONDS = 0.1


class ProcessSemaphore:
    def __init__(self, capacity: int) -> None:
        self._capacity = max(1, int(capacity))
        self._in_use = 0
        self._lock = threading.Lock()

    @property
    def _value(self) -> int:
        with self._lock:
            return max(0, self._capacity - self._in_use)

    def set_capacity(self, capacity: int) -> None:
        with self._lock:
            self._capacity = max(1, int(capacity))

    async def acquire(self, *, token: CancellationToken | None = None) -> bool:
        while True:
            if token is not None:
                token.checkpoint()
            with self._lock:
                if (token is None or not token.is_cancelled) and self._in_use < self._capacity:
                    self._in_use += 1
                    return True
            await cancellable_await(
                asyncio.sleep(ADMISSION_POLL_INTERVAL_SECONDS),
                token=token,
            )

    def release(self) -> None:
        with self._lock:
            if self._in_use <= 0:
                raise ValueError("process semaphore released too many times")
            self._in_use -= 1


_LLM_SEMAPHORE = ProcessSemaphore(settings.max_active_llm_calls)
_DELEGATION_SEMAPHORE = ProcessSemaphore(settings.max_delegated_agent_concurrency)
_FAST_RUN_SEMAPHORE = ProcessSemaphore(settings.max_active_fast_runs)
_DEEP_RUN_SEMAPHORE = ProcessSemaphore(settings.max_active_deep_runs)
_CURRENT_RUN_ADMISSIONS: ContextVar[tuple[str, ...]] = ContextVar(
    "current_run_admissions",
    default=(),
)


def get_llm_semaphore() -> ProcessSemaphore:
    _LLM_SEMAPHORE.set_capacity(settings.max_active_llm_calls)
    return _LLM_SEMAPHORE


def get_delegation_semaphore() -> ProcessSemaphore:
    _DELEGATION_SEMAPHORE.set_capacity(settings.max_delegated_agent_concurrency)
    return _DELEGATION_SEMAPHORE


def get_run_semaphore(mode: str) -> ProcessSemaphore:
    normalized_mode = _normalize_run_mode(mode)
    if normalized_mode == "deep":
        _DEEP_RUN_SEMAPHORE.set_capacity(settings.max_active_deep_runs)
        return _DEEP_RUN_SEMAPHORE
    _FAST_RUN_SEMAPHORE.set_capacity(settings.max_active_fast_runs)
    return _FAST_RUN_SEMAPHORE


def _normalize_run_mode(mode: str) -> str:
    return "deep" if str(mode or "").strip().lower() == "deep" else "fast"


@asynccontextmanager
async def run_slot(
    mode: str,
    *,
    run_id: str,
    token: CancellationToken | None = None,
) -> AsyncIterator[None]:
    normalized_run_id = str(run_id or "").strip()
    if not normalized_run_id:
        raise ValueError("run_id is required for run admission")
    active_runs = _CURRENT_RUN_ADMISSIONS.get()
    if normalized_run_id in active_runs:
        if token is not None:
            token.checkpoint()
        yield
        return

    semaphore = get_run_semaphore(mode)
    acquired = False
    context_token = None
    try:
        await semaphore.acquire(token=token)
        acquired = True
        if token is not None:
            token.checkpoint()
        context_token = _CURRENT_RUN_ADMISSIONS.set((*active_runs, normalized_run_id))
        yield
    finally:
        if context_token is not None:
            try:
                _CURRENT_RUN_ADMISSIONS.reset(context_token)
            except ValueError:
                # aclose()/task switch may exit in a different Context; never block slot release.
                pass
        if acquired:
            semaphore.release()


@asynccontextmanager
async def llm_slot(token: CancellationToken | None = None) -> AsyncIterator[None]:
    _LLM_SEMAPHORE.set_capacity(settings.max_active_llm_calls)
    acquired = False
    try:
        await _LLM_SEMAPHORE.acquire(token=token)
        acquired = True
        if token is not None:
            token.checkpoint()
        yield
    finally:
        if acquired:
            _LLM_SEMAPHORE.release()


@asynccontextmanager
async def delegation_slot(token: CancellationToken | None = None) -> AsyncIterator[None]:
    _DELEGATION_SEMAPHORE.set_capacity(settings.max_delegated_agent_concurrency)
    acquired = False
    try:
        await _DELEGATION_SEMAPHORE.acquire(token=token)
        acquired = True
        if token is not None:
            token.checkpoint()
        yield
    finally:
        if acquired:
            _DELEGATION_SEMAPHORE.release()


llm_admission = llm_slot
delegation_admission = delegation_slot
