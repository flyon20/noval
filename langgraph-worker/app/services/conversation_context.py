from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.models.knowledge import KnowledgeChatRequest


@dataclass(frozen=True)
class ConversationContextProjection:
    summary: str | None
    history: tuple[dict[str, str], ...]

    @property
    def history_texts(self) -> list[str]:
        return [item["content"] for item in self.history]

    @property
    def has_context(self) -> bool:
        return bool(self.summary or self.history)


def project_conversation_context(request: KnowledgeChatRequest) -> ConversationContextProjection:
    summaries: list[str] = []
    history: list[dict[str, str]] = []
    seen_summaries: set[str] = set()
    seen_messages: set[tuple[str, str]] = set()

    def add_summary(value: Any) -> None:
        text = str(value or "").strip()
        if not text or text in seen_summaries:
            return
        seen_summaries.add(text)
        summaries.append(text)

    def add_history(items: Any) -> None:
        if not isinstance(items, (list, tuple)):
            return
        for item in items:
            if not isinstance(item, dict):
                continue
            content = str(item.get("content") or "").strip()
            if not content:
                continue
            role = str(item.get("role") or "user").strip().lower()
            if role not in {"user", "assistant"}:
                role = "user"
            key = (role, content)
            if key in seen_messages:
                continue
            seen_messages.add(key)
            history.append({"role": role, "content": content})

    add_summary(request.contextSummary)
    add_history(request.history)

    bundle = request.contextBundle if isinstance(request.contextBundle, dict) else {}
    thread_layer = bundle.get("threadSummary")
    if isinstance(thread_layer, dict):
        thread_content = thread_layer.get("content")
        if not isinstance(thread_content, dict):
            thread_content = thread_layer
        add_summary(thread_content.get("summary"))
        add_history(thread_content.get("history"))

    return ConversationContextProjection(
        summary="\n".join(summaries) if summaries else None,
        history=tuple(history),
    )
