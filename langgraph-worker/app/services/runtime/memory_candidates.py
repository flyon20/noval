from __future__ import annotations

from app.models.agent_runtime import MemoryCandidate
from app.models.knowledge import KnowledgeChatRequest


class MemoryCandidateExtractor:
    def extract(self, request: KnowledgeChatRequest) -> list[MemoryCandidate]:
        text = " ".join((request.question or "").split())
        if not text:
            return []

        candidates: list[MemoryCandidate] = []
        constraints = self._constraint_content(text)
        if constraints:
            candidates.append(MemoryCandidate(
                scope="project" if request.projectId is not None else "thread",
                type="constraint",
                content=constraints,
                confidence=0.82,
                sourceTraceId=request.traceId,
                reason="explicit writing constraint",
            ))

        if self._has_long_term_marker(text):
            preference = self._preference_content(text)
            if preference:
                candidates.append(MemoryCandidate(
                    scope="user",
                    type="preference",
                    content=preference,
                    confidence=0.78,
                    sourceTraceId=request.traceId,
                    reason="long-term preference marker",
                ))
        elif self._has_temporary_marker(text):
            preference = self._temporary_preference_content(text)
            if preference:
                candidates.append(MemoryCandidate(
                    scope="project" if request.projectId is not None else "thread",
                    type="preference",
                    content=preference,
                    confidence=0.62,
                    sourceTraceId=request.traceId,
                    reason="temporary project preference",
                ))

        return candidates

    def _constraint_content(self, text: str) -> str | None:
        parts: list[str] = []
        if "不后宫" in text or "无后宫" in text or "不要后宫" in text or "不想写后宫" in text:
            parts.append("不后宫")
        if "前三章" in text and ("快节奏" in text or "节奏快" in text):
            parts.append("前三章快节奏")
        elif "快节奏" in text or "节奏快" in text:
            parts.append("快节奏")
        return "；".join(parts) if parts else None

    def _preference_content(self, text: str) -> str | None:
        parts: list[str] = []
        if "番茄" in text:
            parts.append("番茄")
        if "男频" in text:
            parts.append("男频")
        if "都市脑洞" in text:
            parts.append("都市脑洞")
        elif "都市" in text:
            parts.append("都市")
        if not parts:
            return None
        return "".join(parts)

    def _temporary_preference_content(self, text: str) -> str | None:
        parts: list[str] = []
        if "女频" in text:
            parts.append("女频")
        if "甜宠" in text:
            parts.append("甜宠")
        if "慢一点" in text or "慢节奏" in text:
            parts.append("节奏慢一点")
        return "；".join(parts) if parts else None

    def _has_long_term_marker(self, text: str) -> bool:
        return any(marker in text for marker in ("以后", "长期", "一直", "主要", "以后都", "长期都"))

    def _has_temporary_marker(self, text: str) -> bool:
        return any(marker in text for marker in ("这次", "本次", "临时", "试试", "先试"))
