from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any

from app.models.evidence_contract import (
    EvidenceContract,
    EvidenceStatus,
    EvidenceWarning,
    SnapshotGroup,
)
from app.models.knowledge import KnowledgeSource


class EvidenceArbiter:
    def __init__(self, *, max_snapshot_age_days: int = 3) -> None:
        self.max_snapshot_age_days = max_snapshot_age_days

    def evaluate(
        self,
        *,
        intent: str,
        sources: list[KnowledgeSource],
        requested_platform: str | None = None,
        requested_channel_code: str | None = None,
        requested_board_code: str | None = None,
        requested_category: str | None = None,
        top_n: int = 3,
        source_policy: dict[str, Any] | None = None,
        required_evidence: list[str] | tuple[str, ...] | set[str] | None = None,
    ) -> EvidenceContract:
        policy = source_policy or {}
        requested_platform = requested_platform or policy.get("requestedPlatform")
        requested_channel_code = requested_channel_code or policy.get("requestedChannelCode")
        requested_board_code = requested_board_code or policy.get("requestedBoardCode")
        requested_category = requested_category or policy.get("requestedCategory")
        top_n = self._positive_int(top_n or policy.get("topRankLimit"), default=3)
        required = self._required_evidence(required_evidence, policy)

        rank_sources = [
            source
            for source in sources
            if (source.sourceType or "").upper() == "RANK"
        ]
        if not rank_sources:
            if required and not self._requires_rank_evidence(required):
                return self._apply_required_evidence_contract(EvidenceContract(
                    status=EvidenceStatus.verified_latest,
                    selectedSources=list(sources),
                    factualBoundary="selected non-rank evidence only",
                    inferenceBoundary="no rank evidence was used",
                ), required, sources)
            return self._apply_required_evidence_contract(EvidenceContract(
                status=EvidenceStatus.missing,
                warnings=[
                    EvidenceWarning(
                        code="missing_rank_evidence",
                        message="No usable structured rank evidence was available.",
                    )
                ],
                requiredActions=["rank.lookup"],
            ), required, sources)

        grouped_sources = self._group_rank_sources(rank_sources)
        groups = [
            self._build_snapshot_group(
                group_id=group_id,
                sources=group_sources,
                requested_platform=requested_platform,
                requested_channel_code=requested_channel_code,
                requested_board_code=requested_board_code,
                requested_category=requested_category,
                top_n=top_n,
            )
            for group_id, group_sources in grouped_sources.items()
        ]
        if not groups:
            return self._apply_required_evidence_contract(
                EvidenceContract(status=EvidenceStatus.missing, requiredActions=["rank.lookup"]),
                required,
                sources,
            )

        selected_group = max(groups, key=lambda group: group.score)
        selected_sources = self._sort_rank_sources(grouped_sources[selected_group.groupId])
        rejected_groups = sorted(
            [group for group in groups if group.groupId != selected_group.groupId],
            key=lambda group: group.score,
            reverse=True,
        )
        reference_signals = [
            self._reference_signal(group)
            for group in rejected_groups
        ]
        warnings = self._warnings_for_groups(
            selected_group=selected_group,
            rejected_groups=rejected_groups,
        )
        is_mixed_creation = intent == "mixed_creation_research"
        has_mixed_snapshots = self._has_mixed_snapshot_markers(groups)
        selected_stale = self._is_stale(selected_group)
        selected_usable = selected_group.topRankCoverage > 0
        selected_matches_request = (
            selected_group.categoryMatch
            and selected_group.channelMatch
            and selected_group.boardMatch
        )

        if not selected_usable:
            status = EvidenceStatus.missing
            required_actions = ["refresh_rank_board"]
        elif has_mixed_snapshots and not is_mixed_creation:
            status = EvidenceStatus.conflict
            required_actions = ["refresh_rank_board"]
        elif not selected_matches_request:
            status = EvidenceStatus.conflict
            required_actions = ["rank.lookup"]
        elif selected_stale and not is_mixed_creation:
            status = EvidenceStatus.stale
            required_actions = ["refresh_rank_board"]
        elif is_mixed_creation and (
            has_mixed_snapshots
            or not selected_group.snapshotComplete
            or selected_stale
        ):
            status = EvidenceStatus.degraded_directional
            required_actions = []
            warnings.append(
                EvidenceWarning(
                    code="directional_rank_evidence",
                    message="Rank evidence is usable as directional creative context only.",
                )
            )
        elif selected_group.snapshotComplete:
            status = EvidenceStatus.verified_latest
            required_actions = []
        else:
            status = EvidenceStatus.missing
            required_actions = ["refresh_rank_board"]

        return self._apply_required_evidence_contract(EvidenceContract(
            status=status,
            selectedSources=selected_sources,
            referenceSignals=reference_signals,
            warnings=warnings,
            selectedSnapshotGroup=selected_group,
            rejectedGroups=rejected_groups,
            requiredActions=required_actions,
        ), required, sources)

    def _apply_required_evidence_contract(
        self,
        contract: EvidenceContract,
        required_evidence: list[str],
        sources: list[KnowledgeSource],
    ) -> EvidenceContract:
        missing = [
            requirement
            for requirement in required_evidence
            if not self._has_required_evidence(requirement, sources)
            and not self._satisfies_directional_rank_requirement(requirement, contract)
        ]
        if not missing:
            return contract
        warnings = list(contract.warnings)
        warnings.append(
            EvidenceWarning(
                code="missing_required_evidence",
                message="Required evidence is missing: " + ", ".join(missing),
                severity="error",
            )
        )
        required_actions = list(dict.fromkeys(list(contract.requiredActions) + missing))
        return contract.model_copy(
            update={
                "status": EvidenceStatus.missing,
                "warnings": warnings,
                "requiredActions": required_actions,
            }
        )

    def _satisfies_directional_rank_requirement(
        self,
        requirement: str,
        contract: EvidenceContract,
    ) -> bool:
        if contract.status != EvidenceStatus.degraded_directional:
            return False
        if self._normalize(requirement) not in {"fresh_rank", "current_structured_rank_topn", "rank_evidence"}:
            return False
        group = contract.selectedSnapshotGroup
        return group is not None and group.topRankCoverage > 0

    def _required_evidence(
        self,
        required_evidence: list[str] | tuple[str, ...] | set[str] | None,
        policy: dict[str, Any],
    ) -> list[str]:
        raw = required_evidence if required_evidence is not None else policy.get("requiredEvidence")
        if not raw:
            return []
        if isinstance(raw, str):
            return [raw]
        return [str(item) for item in raw if str(item).strip()]

    def _has_required_evidence(self, requirement: str, sources: list[KnowledgeSource]) -> bool:
        normalized = self._normalize(requirement)
        source_types = {(source.sourceType or "").upper() for source in sources}
        if normalized in {"fresh_rank", "current_structured_rank_topn", "rank_evidence"}:
            return any(
                (source.sourceType or "").upper() == "RANK"
                and (source.snapshotId is not None or bool(source.snapshotTime))
                for source in sources
            )
        if normalized in {"book_chapter", "chapter_evidence", "chapter"}:
            return "CHAPTER" in source_types
        if normalized in {"book_analysis", "analysis"}:
            return "ANALYSIS" in source_types
        if normalized in {"vector_evidence", "knowledge_vector"}:
            return bool(sources)
        return any(self._normalize(source.sourceType) == normalized for source in sources)

    def _requires_rank_evidence(self, requirements: list[str]) -> bool:
        return any(
            self._normalize(requirement) in {"fresh_rank", "current_structured_rank_topn", "rank_evidence"}
            for requirement in requirements
        )

    def _group_rank_sources(self, sources: list[KnowledgeSource]) -> dict[str, list[KnowledgeSource]]:
        groups: dict[str, list[KnowledgeSource]] = {}
        for source in sources:
            key = self._group_key(source)
            groups.setdefault(key, []).append(source)
        return groups

    def _group_key(self, source: KnowledgeSource) -> str:
        values = [
            self._clean(source.platform),
            self._clean(source.channelCode),
            self._clean(source.boardCode or source.category),
            self._clean(source.snapshotId if source.snapshotId is not None else source.snapshotTime),
            self._clean(source.retrievalBackend or "unknown_tool"),
        ]
        return "|".join(values)

    def _build_snapshot_group(
        self,
        *,
        group_id: str,
        sources: list[KnowledgeSource],
        requested_platform: str | None,
        requested_channel_code: str | None,
        requested_board_code: str | None,
        requested_category: str | None,
        top_n: int,
    ) -> SnapshotGroup:
        first = sources[0]
        rank_values = sorted({source.rankNo for source in sources if source.rankNo is not None})
        book_ids = sorted({source.bookId for source in sources if source.bookId is not None})
        top_coverage = len([rank for rank in rank_values if rank <= top_n])
        snapshot_times = sorted({source.snapshotTime for source in sources if source.snapshotTime})
        snapshot_ids = sorted({source.snapshotId for source in sources if source.snapshotId is not None})
        snapshot_time = snapshot_times[-1] if snapshot_times else None
        snapshot_id = snapshot_ids[-1] if snapshot_ids else None
        age_days = self._snapshot_age_days(snapshot_time)
        group = SnapshotGroup(
            groupId=group_id,
            platform=first.platform,
            channelCode=first.channelCode,
            boardCode=first.boardCode,
            category=first.category,
            snapshotId=snapshot_id,
            snapshotTime=snapshot_time,
            sourceTool=first.retrievalBackend,
            sourceCount=len(sources),
            topRankCoverage=top_coverage,
            ranks=rank_values,
            bookIds=book_ids,
            snapshotComplete=bool(snapshot_id is not None or snapshot_time),
            categoryMatch=self._matches_requested(
                requested_category,
                [first.category, first.boardName, first.boardCode, first.title, first.preview],
            ),
            channelMatch=self._matches_requested(
                requested_channel_code,
                [first.channelCode, first.channelName, first.title, first.preview],
            ),
            boardMatch=self._matches_requested(
                requested_board_code,
                [first.boardCode, first.boardName, first.title, first.preview],
            ),
            snapshotAgeDays=round(age_days, 3) if age_days is not None else None,
        )
        group.score = self._score_group(group)
        if requested_platform and self._normalize(first.platform) != self._normalize(requested_platform):
            group.score -= 25
        return group

    def _score_group(self, group: SnapshotGroup) -> float:
        score = group.topRankCoverage * 100
        score += min(group.sourceCount, 20) * 2
        if group.snapshotComplete:
            score += 30
        score += self._source_priority(group.sourceTool)
        if group.snapshotAgeDays is not None:
            score += max(0.0, 20.0 - group.snapshotAgeDays)
        if group.categoryMatch:
            score += 10
        if group.channelMatch:
            score += 10
        if group.boardMatch:
            score += 10
        return score

    def _source_priority(self, source_tool: str | None) -> int:
        normalized = (source_tool or "").replace("_", ".")
        if normalized == "rank.research.pack" or normalized == "rank.research_pack":
            return 30
        if normalized == "rank.lookup":
            return 20
        if normalized == "vector.rank.search" or normalized == "vector_rank_search":
            return 10
        return 0

    def _warnings_for_groups(
        self,
        *,
        selected_group: SnapshotGroup,
        rejected_groups: list[SnapshotGroup],
    ) -> list[EvidenceWarning]:
        warnings: list[EvidenceWarning] = []
        if rejected_groups:
            warnings.append(
                EvidenceWarning(
                    code="mixed_structured_rank_snapshot",
                    message="Multiple structured rank snapshot groups were found; one group was selected for the answer.",
                )
            )
        for group in rejected_groups:
            warnings.append(
                EvidenceWarning(
                    code="reference_snapshot_group",
                    message=(
                        "A non-selected rank snapshot group was retained as a reference signal "
                        f"from {group.sourceTool or 'unknown tool'}."
                    ),
                )
            )
        if self._is_stale(selected_group):
            warnings.append(
                EvidenceWarning(
                    code="stale_structured_rank_snapshot",
                    message="The selected rank snapshot is older than the configured freshness window.",
                )
            )
        if not selected_group.snapshotComplete:
            warnings.append(
                EvidenceWarning(
                    code="missing_structured_rank_snapshot",
                    message="The selected rank rows do not include snapshotTime or snapshotId.",
                )
            )
        return warnings

    def _reference_signal(self, group: SnapshotGroup) -> dict[str, Any]:
        return group.model_dump(mode="json", exclude_none=True)

    def _has_mixed_snapshot_markers(self, groups: list[SnapshotGroup]) -> bool:
        markers = {
            (group.snapshotId, group.snapshotTime)
            for group in groups
            if group.snapshotId is not None or group.snapshotTime
        }
        return len(markers) > 1

    def _is_stale(self, group: SnapshotGroup) -> bool:
        return group.snapshotAgeDays is not None and group.snapshotAgeDays > self.max_snapshot_age_days

    def _snapshot_age_days(self, snapshot_time: str | None) -> float | None:
        if not snapshot_time:
            return None
        parsed = self._parse_snapshot_time(snapshot_time)
        if parsed is None:
            return None
        age_seconds = (datetime.now(timezone.utc) - parsed).total_seconds()
        return max(0.0, age_seconds / 86400)

    def _parse_snapshot_time(self, snapshot_time: str) -> datetime | None:
        value = str(snapshot_time or "").strip()
        if not value:
            return None
        if value.endswith("Z"):
            value = f"{value[:-1]}+00:00"
        try:
            parsed = datetime.fromisoformat(value)
        except ValueError:
            return None
        if parsed.tzinfo is None:
            return parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc)

    def _sort_rank_sources(self, sources: list[KnowledgeSource]) -> list[KnowledgeSource]:
        return sorted(
            sources,
            key=lambda source: (
                source.rankNo if source.rankNo is not None else 9999,
                source.bookId if source.bookId is not None else 999999999,
            ),
        )

    def _matches_requested(self, requested: str | None, candidates: list[Any]) -> bool:
        normalized_requested = self._normalize(requested)
        if not normalized_requested:
            return True
        for candidate in candidates:
            normalized_candidate = self._normalize(candidate)
            if not normalized_candidate:
                continue
            if (
                normalized_candidate == normalized_requested
                or normalized_requested in normalized_candidate
                or normalized_candidate in normalized_requested
            ):
                return True
        return False

    def _normalize(self, value: Any) -> str:
        if value is None:
            return ""
        return re.sub(r"\s+", "", str(value)).casefold()

    def _clean(self, value: Any) -> str:
        normalized = self._normalize(value)
        return normalized or "-"

    def _positive_int(self, value: Any, *, default: int) -> int:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return default
        return max(1, parsed)
