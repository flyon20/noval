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

    def test_exact_historical_range_accepts_old_multi_snapshot_evidence(self) -> None:
        sources = [
            rank_source(
                book_id=snapshot_id * 10 + index,
                rank_no=index,
                snapshot_id=snapshot_id,
                snapshot_time=snapshot_time,
                tool_name="rank.lookup",
            )
            for snapshot_id, snapshot_time in (
                (100, "2026-08-05T10:00:00+00:00"),
                (200, "2026-08-08T10:00:00+00:00"),
            )
            for index in range(1, 4)
        ]

        contract = self.arbiter.evaluate(
            intent="market_scan",
            sources=sources,
            source_policy={
                "allowHistorical": True,
                "snapshotStartDate": "2026-08-03",
                "snapshotEndDate": "2026-08-09",
                "currentRankLimit": 3,
            },
            required_evidence=["historical_rank_snapshot"],
        )

        self.assertEqual("verified_latest", contract.status)
        self.assertEqual(200, contract.selectedSnapshotGroup.snapshotId)
        self.assertEqual([], contract.requiredActions)
        self.assertIn("historical snapshot", contract.factualBoundary)
        self.assertIn("requested range", contract.factualBoundary)
        self.assertTrue(any(
            warning.code == "historical_comparison_snapshots"
            for warning in contract.warnings
        ))
        self.assertFalse(any(
            warning.code == "stale_structured_rank_snapshot"
            for warning in contract.warnings
        ))

    def test_exact_historical_range_groups_different_boards_by_calendar_day(self) -> None:
        sources = [
            rank_source(
                book_id=snapshot_base * 10 + board_index,
                rank_no=1,
                snapshot_id=snapshot_base + board_index,
                snapshot_time=f"{snapshot_date}T10:{board_index:02d}:00+00:00",
                tool_name="rank.lookup",
            ).model_copy(update={
                "boardCode": f"board-{board_index}",
                "category": f"category-{board_index}",
            })
            for snapshot_base, snapshot_date in (
                (100, "2026-08-03"),
                (200, "2026-08-09"),
            )
            for board_index in range(1, 6)
        ]

        contract = self.arbiter.evaluate(
            intent="market_scan",
            sources=sources,
            source_policy={
                "allowHistorical": True,
                "snapshotStartDate": "2026-08-03",
                "snapshotEndDate": "2026-08-09",
                "currentRankLimit": 5,
            },
            required_evidence=["historical_rank_snapshot"],
        )

        self.assertEqual("verified_latest", contract.status)
        self.assertEqual(5, contract.selectedSnapshotGroup.sourceCount)
        self.assertEqual(1, contract.selectedSnapshotGroup.topRankCoverage)
        self.assertEqual([], contract.rejectedGroups)
        self.assertEqual(
            ["2026-08-03", "2026-08-09"],
            sorted({source.snapshotTime[:10] for source in contract.selectedSources}),
        )
        commit = self.arbiter.to_evidence_commit(
            contract,
            sources=sources,
            intent="market_scan",
        )
        self.assertTrue(commit.canCommit)
        self.assertTrue(all(item.decision.value == "ACCEPTED" for item in commit.decisions))

    def test_scoped_historical_board_still_requires_requested_rank_coverage(self) -> None:
        sources = [
            rank_source(
                book_id=snapshot_base * 10 + row_index,
                rank_no=1,
                snapshot_id=snapshot_base + row_index,
                snapshot_time=f"{snapshot_date}T10:{row_index:02d}:00+00:00",
                tool_name="rank.lookup",
            )
            for snapshot_base, snapshot_date in (
                (100, "2026-08-03"),
                (200, "2026-08-09"),
            )
            for row_index in range(1, 6)
        ]

        contract = self.arbiter.evaluate(
            intent="market_scan",
            sources=sources,
            requested_board_code="urban-brain",
            source_policy={
                "allowHistorical": True,
                "snapshotStartDate": "2026-08-03",
                "snapshotEndDate": "2026-08-09",
                "currentRankLimit": 5,
            },
            required_evidence=["historical_rank_snapshot"],
        )

        self.assertEqual("missing", contract.status)
        self.assertTrue(any(
            warning.code == "incomplete_structured_rank_snapshot"
            for warning in contract.warnings
        ))

    def test_current_structured_topn_requires_requested_coverage(self) -> None:
        sources = [
            rank_source(
                book_id=1000 + index,
                rank_no=index,
                snapshot_id=100,
                snapshot_time=CURRENT_SNAPSHOT_TIME,
                tool_name="rank.lookup",
            )
            for index in range(1, 11)
        ]

        contract = self.arbiter.evaluate(
            intent="market_scan",
            sources=sources,
            source_policy={
                "currentRankLimit": 30,
                "requiredEvidence": "current_structured_rank_topn",
            },
        )

        self.assertEqual("missing", contract.status)
        self.assertIn("refresh_rank_board", contract.requiredActions)
        self.assertEqual(10, contract.selectedSnapshotGroup.topRankCoverage)

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

    def test_project_bound_requirement_accepts_only_scoped_project_evidence(self) -> None:
        unscoped = KnowledgeSource(sourceType="PROJECT_CHUNK", preview="unscoped")
        scoped = KnowledgeSource(
            sourceType="PROJECT_FORESHADOWING",
            projectId=91,
            workId=92,
            title="月背管理员信号",
            preview="尚未回收",
        )

        missing = self.arbiter.evaluate(
            intent="project_knowledge",
            sources=[unscoped],
            required_evidence=["project_bound_chapter_or_memory_evidence"],
        )
        verified = self.arbiter.evaluate(
            intent="project_knowledge",
            sources=[scoped],
            required_evidence=["project_bound_chapter_or_memory_evidence"],
        )

        self.assertEqual("missing", missing.status)
        self.assertEqual("verified_latest", verified.status)



    def test_commit_rejects_forged_citation_and_blocks_commit(self) -> None:
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
        commit = self.arbiter.commit(
            intent="market_scan",
            sources=sources,
            requested_channel_code="male-new",
            requested_category="urban-brain",
            top_n=3,
            claimed_citations=["1", "99"],
        )
        self.assertFalse(commit.canCommit)
        self.assertIn("forged_citation", commit.reasonCodes)
        self.assertTrue(any(item.decision.value == "REJECTED" and "forged_citation" in item.reasonCodes for item in commit.decisions))

    def test_commit_rejects_stale_market_and_allows_one_repair(self) -> None:
        from datetime import timedelta

        stale_time = (datetime.now(timezone.utc) - timedelta(days=10)).replace(microsecond=0).isoformat()
        sources = [
            rank_source(
                book_id=1000 + index,
                rank_no=index,
                snapshot_id=100,
                snapshot_time=stale_time,
                tool_name="rank.lookup",
            )
            for index in range(1, 4)
        ]
        commit = self.arbiter.commit(
            intent="market_scan",
            sources=sources,
            requested_channel_code="male-new",
            requested_category="urban-brain",
            top_n=3,
        )
        self.assertFalse(commit.canCommit)
        self.assertTrue(commit.repairAllowed)
        self.assertIn("stale_market_claim", commit.reasonCodes)

        sealed = self.arbiter.commit(
            intent="market_scan",
            sources=sources,
            requested_channel_code="male-new",
            requested_category="urban-brain",
            top_n=3,
            repair_already_used=True,
        )
        self.assertFalse(sealed.repairAllowed)
        self.assertIn("repair_budget_exhausted", sealed.reasonCodes)

    def test_commit_rejects_cross_project_evidence(self) -> None:
        sources = [
            KnowledgeSource(
                sourceType="PROJECT_CHUNK",
                projectId=11,
                workId=22,
                title="other project",
                preview="leaked notes",
            )
        ]
        commit = self.arbiter.commit(
            intent="project_knowledge",
            sources=sources,
            required_evidence=["project_bound_chapter_or_memory_evidence"],
            expected_project_id=99,
        )
        self.assertFalse(commit.canCommit)
        self.assertIn("cross_project_evidence", commit.reasonCodes)

    def test_commit_accepts_only_exact_authenticated_reference_scope(self) -> None:
        allowed = KnowledgeSource(
            sourceType="PROJECT_CHUNK",
            projectId=11,
            workId=22,
            title="selected reference",
            preview="selected evidence",
        )
        rejected = KnowledgeSource(
            sourceType="PROJECT_CHUNK",
            projectId=11,
            workId=23,
            title="unselected work",
            preview="must not leak",
        )

        accepted_commit = self.arbiter.commit(
            intent="project_knowledge",
            sources=[allowed],
            required_evidence=["project_bound_chapter_or_memory_evidence"],
            allowed_project_work_scopes={(99, 100), (11, 22)},
        )
        rejected_commit = self.arbiter.commit(
            intent="project_knowledge",
            sources=[allowed, rejected],
            required_evidence=["project_bound_chapter_or_memory_evidence"],
            allowed_project_work_scopes={(99, 100), (11, 22)},
        )

        self.assertTrue(accepted_commit.canCommit)
        self.assertFalse(rejected_commit.canCommit)
        self.assertIn("cross_project_evidence", rejected_commit.reasonCodes)

    def test_commit_accepts_verified_selected_sources(self) -> None:
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
        commit = self.arbiter.commit(
            intent="market_scan",
            sources=sources,
            requested_channel_code="male-new",
            requested_category="urban-brain",
            top_n=3,
            claimed_citations=["1", "2"],
        )
        self.assertTrue(commit.canCommit)
        self.assertFalse(commit.repairAllowed)
        self.assertTrue(commit.trace_summary()["evidenceIds"])

if __name__ == "__main__":
    unittest.main()
