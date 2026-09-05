from __future__ import annotations

from typing import Any

from app.models.agent_task import EvidencePack
from app.models.knowledge import KnowledgeSource


class EvidencePackBuilder:
    def from_sources(
        self,
        sources: list[KnowledgeSource],
        *,
        inference_signals: list[dict[str, Any]] | None = None,
    ) -> EvidencePack:
        pack = EvidencePack()
        for index, source in enumerate(sources, start=1):
            item = self._source_summary(source, index)
            source_type = str(source.sourceType or source.analysisType or "").upper()
            if source_type == "RANK" or source.rankNo is not None:
                pack.facts.append(item)
            elif source_type.startswith("PROJECT_") or source_type in {
                "CHAPTER",
                "INTRO",
                "ANALYSIS",
                "CHAPTER_PACK",
                "PROJECT_CHAPTER",
                "PROJECT_CHUNK",
                "PROJECT_FORESHADOWING",
                "PROJECT_TIMELINE",
                "PROJECT_CHARACTER_STATE",
                "PROJECT_WORLD_RULE",
            }:
                pack.examples.append(item)
            else:
                pack.inferenceSeeds.append(item)
        for signal in inference_signals or []:
            pack.signals.append(dict(signal))
        return pack

    def _source_summary(self, source: KnowledgeSource, index: int) -> dict[str, Any]:
        return {
            "ref": f"source:{index}",
            "chunkId": source.chunkId,
            "documentId": source.documentId,
            "sourceRefId": source.sourceRefId,
            "sourceType": source.sourceType,
            "score": source.score,
            "retrievalBackend": source.retrievalBackend,
            "retrievalChannel": self._retrieval_channel(source.retrievalBackend),
            "bookId": source.bookId,
            "bookName": source.bookName,
            "projectId": source.projectId,
            "workId": source.workId,
            "chapterId": source.chapterId,
            "sceneId": source.sceneId,
            "generationId": source.generationId,
            "chapterVersion": source.chapterVersion,
            "rankNo": source.rankNo,
            "snapshotId": source.snapshotId,
            "snapshotTime": source.snapshotTime,
            "channelCode": source.channelCode,
            "boardCode": source.boardCode,
            "category": source.category,
            "title": source.title,
            "preview": self._short_text(source.preview or source.material or "", 220),
        }

    def _retrieval_channel(self, backend: str | None) -> str | None:
        normalized = str(backend or "").strip().lower()
        if normalized == "qdrant":
            return "vector"
        if normalized == "lexical":
            return "fulltext"
        return normalized or None

    def _short_text(self, value: str, max_length: int) -> str:
        compact = " ".join((value or "").split())
        if len(compact) <= max_length:
            return compact
        return compact[:max_length] + "..."
