"""Bounded host progress; never part of a model request or tool observation."""

from collections.abc import Callable, Iterator
from contextlib import contextmanager
from contextvars import ContextVar
import re
from typing import Any


MAX_TOOL_PROGRESS_ITEMS = 64
_ITEM_ID = re.compile(r"tool_[0-9a-f]{32}")
_TOOL_NAME = re.compile(r"[A-Za-z0-9_.:-]{1,80}")
_MESSAGES = {
    "running": "正在调用工具",
    "completed": "工具调用完成",
    "failed": "工具调用失败",
    "cancelled": "工具调用已取消",
    "unknown": "工具结果待确认",
}
_WRITER: ContextVar[Callable[[dict[str, Any]], None] | None] = ContextVar(
    "harness_tool_progress_writer", default=None,
)


def tool_progress_payload(item_id: Any, name: str, status: str) -> dict[str, Any] | None:
    if not isinstance(item_id, str) or _ITEM_ID.fullmatch(item_id) is None:
        return None
    item_status = {
        "succeeded": "completed", "timed_out": "failed", "denied": "failed",
        "invalidated": "failed",
    }.get(status, status)
    if item_status not in _MESSAGES:
        item_status = "unknown"
    return {
        "event": "progress", "schemaVersion": 1, "phase": "tools",
        "message": _MESSAGES[item_status], "itemId": item_id,
        "itemType": "tool", "itemStatus": item_status,
        "toolName": name if _TOOL_NAME.fullmatch(name) else "tool",
    }


def emit_tool_progress(item_id: Any, name: str, status: str) -> None:
    writer = _WRITER.get()
    payload = tool_progress_payload(item_id, name, status)
    if writer is None or payload is None:
        return
    try:
        writer(payload)
    except Exception:
        # The semantic checkpoint owns the outcome. A display failure must not retry a tool.
        pass


@contextmanager
def tool_progress_scope(writer: Callable[[dict[str, Any]], None] | None) -> Iterator[None]:
    token = _WRITER.set(writer)
    try:
        yield
    finally:
        _WRITER.reset(token)
