from __future__ import annotations

import unittest
from datetime import datetime, timezone

from app.models.knowledge import KnowledgeSource
from app.services.runtime.evidence_arbiter import EvidenceArbiter


CURRENT_SNAPSHOT_TIME = datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def rank_source(
    *,
    book_id: int,
    rank_no: int,
    snapshot_id: int | None,
    snapshot_time: str | None,
    tool_name: str,
    category: str = "urban-brain",
) -> KnowledgeSource:
    return KnowledgeSource(
        bookId=book_id,
        bookName=f"Rank Book {book_id}",
        platform="fanqie",
        sourceType="RANK",
        snapshotId=snapshot_id,
        snapshotTime=snapshot_time,
        channelCode="male-new",
        boardCode="urban-brain",
        category=category,
        rankNo=rank_no,
        title=f"male-new / urban-brain #{rank_no}",
        preview=f"rank row {rank_no}",
        retrievalBackend=tool_name,
    )


class EvidenceArbiterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.arbiter = EvidenceArbiter(max_snapshot_age_days=3)

    def test_selects_single_snapshot_group_when_lookup_and_pack_disagree(self) -> None:
        lookup_rows = [
            rank_source(
                book_id=1000 + index,
                rank_no=index,
                snapshot_id=100,
                snapshot_time=CURRENT_SNAPSHOT_TIME,
                tool_name="rank.lookup",
            )
            for index in range(1, 4)
        ]
        pack_rows = [
            rank_source(
                book_id=2000 + index,
                rank_no=index,
                snapshot_id=200,
                snapshot_time=CURRENT_SNAPSHOT_TIME,
                tool_name="rank.research_pack",
            )
            for index in range(1, 11)
        ]

        contract = self.arbiter.evaluate(
            intent="mixed_creation_research",
            sources=lookup_rows + pack_rows,
            requested_channel_code="male-new",
            requested_category="urban-brain",
            top_n=3,
        )

        self.assertEqual("degraded_directional", contract.status)
        self.assertEqual(200, contract.selectedSnapshotGroup.snapshotId)
        self.assertEqual("rank.research_pack", contract.selectedSnapshotGroup.sourceTool)
        self.assertEqual([2001, 2002, 2003], [source.bookId for source in contract.selectedSources[:3]])
        self.assertTrue(contract.rejectedGroups)

    def test_pure_market_conflict_requires_refresh_or_blocks(self) -> None:
        sources = [
            rank_source(
                book_id=1000 + index,
                rank_no=index,
                snapshot_id=100,
                snapshot_time=CURRENT_SNAPSHOT_TIME,
                tool_name="rank.lookup",
            )
            for index in range(1, 4)
        ] + [
            rank_source(
                book_id=2000 + index,
                rank_no=index,
                snapshot_id=200,
                snapshot_time=CURRENT_SNAPSHOT_TIME,
                tool_name="rank.research_pack",
            )
            for index in range(1, 4)
        ]

        contract = self.arbiter.evaluate(
            intent="market_scan",
            sources=sources,
            requested_channel_code="male-new",
            requested_category="urban-brain",
            top_n=3,
        )

        self.assertEqual("conflict", contract.status)
        self.assertIn("refresh_rank_board", contract.requiredActions)
        self.assertTrue(any(warning.code == "mixed_structured_rank_snapshot" for warning in contract.warnings))

    def test_mixed_creation_mixed_snapshot_returns_degraded_directional(self) -> None:
        sources = [
            rank_source(
                book_id=1000 + index,
                rank_no=index,
                snapshot_id=100,
                snapshot_time=CURRENT_SNAPSHOT_TIME,
                tool_name="rank.lookup",
            )
            for index in range(1, 4)
        ] + [
            rank_source(
                book_id=2000 + index,
                rank_no=index,
                snapshot_id=200,
                snapshot_time=CURRENT_SNAPSHOT_TIME,
                tool_name="rank.research_pack",
            )
            for index in range(1, 4)
        ]

        contract = self.arbiter.evaluate(
            intent="mixed_creation_research",
            sources=sources,
            requested_channel_code="male-new",
            requested_category="urban-brain",
            top_n=3,
        )

        self.assertEqual("degraded_directional", contract.status)
        self.assertFalse(contract.requiredActions)

    def test_mixed_creation_snapshotless_rank_satisfies_directional_topn_requirement(self) -> None:
        sources = [
            rank_source(
                book_id=3000 + index,
                rank_no=index,
                snapshot_id=None,
                snapshot_time=None,
                tool_name="rank.lookup",
            )
            for index in range(1, 11)
        ]

        contract = self.arbiter.evaluate(
            intent="mixed_creation_research",
            sources=sources,
            requested_channel_code="male-new",
            requested_category="urban-brain",
            top_n=3,
            required_evidence=["current_structured_rank_topn"],
        )

        self.assertEqual("degraded_directional", contract.status)
        self.assertFalse(contract.requiredActions)

    def test_reference_groups_are_preserved_as_signals(self) -> None:
        selected_rows = [
            rank_source(
                book_id=2000 + index,
                rank_no=index,
                snapshot_id=200,
                snapshot_time=CURRENT_SNAPSHOT_TIME,
                tool_name="rank.research_pack",
            )
            for index in range(1, 4)
        ]
        rejected_rows = [
            rank_source(
                book_id=1000 + index,
                rank_no=index,
                snapshot_id=100,
                snapshot_time=CURRENT_SNAPSHOT_TIME,
                tool_name="rank.lookup",
            )
            for index in range(1, 4)
        ]

        contract = self.arbiter.evaluate(
            intent="mixed_creation_research",
            sources=rejected_rows + selected_rows,
            requested_channel_code="male-new",
            requested_category="urban-brain",
            top_n=3,
        )

        self.assertTrue(contract.referenceSignals)
        self.assertEqual(100, contract.referenceSignals[0]["snapshotId"])
        self.assertEqual(100, contract.rejectedGroups[0].snapshotId)
        self.assertTrue(any(warning.code == "reference_snapshot_group" for warning in contract.warnings))

    def test_required_evidence_contract_blocks_when_required_source_type_is_missing(self) -> None:
        sources = [
            rank_source(
                book_id=1000 + index,
                rank_no=index,
                snapshot_id=100,
                snapshot_time=CURRENT_SNAPSHOT_TIME,
                tool_name="rank.lookup",
            )
            for index in range(1, 4)
        ]

        contract = self.arbiter.evaluate(
            intent="market_scan",
            sources=sources,
            requested_channel_code="male-new",
            requested_category="urban-brain",
            top_n=3,
            required_evidence=["fresh_rank", "book_chapter"],
        )

        self.assertEqual("missing", contract.status)
        self.assertIn("book_chapter", contract.requiredActions)
        self.assertTrue(any(warning.code == "missing_required_evidence" for warning in contract.warnings))

    def test_chapter_required_evidence_passes_without_rank_sources(self) -> None:
        sources = [
            KnowledgeSource(
                sourceType="CHAPTER",
                bookId=101,
                bookName="Chapter Book",
                title="Chapter 1",
                preview="chapter evidence",
            )
        ]

        contract = self.arbiter.evaluate(
            intent="single_book_research",
            sources=sources,
            required_evidence=["chapter_evidence", "book_chapter"],
        )

        self.assertEqual("verified_latest", contract.status)
        self.assertEqual([], contract.requiredActions)
        self.assertFalse(any(warning.code == "missing_rank_evidence" for warning in contract.warnings))


if __name__ == "__main__":
    unittest.main()
