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
            elif source_type in {"CHAPTER", "INTRO", "ANALYSIS", "CHAPTER_PACK"}:
                pack.examples.append(item)
            else:
                pack.inferenceSeeds.append(item)
        for signal in inference_signals or []:
            pack.signals.append(dict(signal))
        return pack

    def _source_summary(self, source: KnowledgeSource, index: int) -> dict[str, Any]:
        return {
            "ref": f"source:{index}",
            "sourceType": source.sourceType,
            "bookId": source.bookId,
            "bookName": source.bookName,
            "rankNo": source.rankNo,
            "snapshotId": source.snapshotId,
            "snapshotTime": source.snapshotTime,
            "channelCode": source.channelCode,
            "boardCode": source.boardCode,
            "category": source.category,
            "title": source.title,
            "preview": self._short_text(source.preview or source.material or "", 220),
        }

    def _short_text(self, value: str, max_length: int) -> str:
        compact = " ".join((value or "").split())
        if len(compact) <= max_length:
            return compact
        return compact[:max_length] + "..."
