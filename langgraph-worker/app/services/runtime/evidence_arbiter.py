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
from app.services.harness.contracts import (
    EvidenceCommit,
    EvidenceDecision,
    EvidenceDecisionState,
)


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
        historical_range = bool(
            policy.get("allowHistorical")
            and policy.get("snapshotStartDate")
            and policy.get("snapshotEndDate")
        )
        requested_platform = requested_platform or policy.get("requestedPlatform")
        requested_channel_code = requested_channel_code or policy.get("requestedChannelCode")
        requested_board_code = requested_board_code or policy.get("requestedBoardCode")
        requested_category = requested_category or policy.get("requestedCategory")
        enforce_requested_top_n = self._positive_int(policy.get("currentRankLimit"), default=0) > 0
        top_n = self._positive_int(
            policy.get("currentRankLimit") or top_n or policy.get("topRankLimit"),
            default=3,
        )
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

        grouped_sources = self._group_rank_sources(
            rank_sources,
            historical_range=historical_range,
        )
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

        selected_group = max(
            groups,
            key=(
                (lambda group: (group.snapshotTime or "", group.score))
                if historical_range
                else (lambda group: group.score)
            ),
        )
        selected_sources = self._sort_rank_sources(grouped_sources[selected_group.groupId])
        comparison_groups = sorted(
            [group for group in groups if group.groupId != selected_group.groupId],
            key=lambda group: group.score,
            reverse=True,
        )
        contract_selected_sources = selected_sources
        rejected_groups = comparison_groups
        if historical_range:
            contract_selected_sources = [
                source
                for group in sorted(
                    groups,
                    key=lambda item: (item.snapshotTime or "", item.score),
                    reverse=True,
                )
                for source in self._sort_rank_sources(grouped_sources[group.groupId])
            ]
            rejected_groups = []
        reference_signals = [
            self._reference_signal(group)
            for group in comparison_groups
        ]
        warnings = self._warnings_for_groups(
            selected_group=selected_group,
            rejected_groups=comparison_groups,
            historical_range=historical_range,
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
        broad_historical_sample_complete = self._has_broad_historical_rank_coverage(
            selected_sources,
            historical_range=historical_range,
            requested_channel_code=requested_channel_code,
            requested_board_code=requested_board_code,
            requested_category=requested_category,
            top_n=top_n,
        )

        if not selected_usable:
            status = EvidenceStatus.missing
            required_actions = ["refresh_rank_board"]
        elif (
            enforce_requested_top_n
            and self._is_pure_market_intent(intent)
            and selected_group.topRankCoverage < top_n
            and not broad_historical_sample_complete
        ):
            status = EvidenceStatus.missing
            required_actions = ["refresh_rank_board"]
            warnings.append(
                EvidenceWarning(
                    code="incomplete_structured_rank_snapshot",
                    message=f"Current rank snapshot covers {selected_group.topRankCoverage} of requested Top{top_n}.",
                    severity="error",
                )
            )
        elif has_mixed_snapshots and not is_mixed_creation and not historical_range:
            status = EvidenceStatus.conflict
            required_actions = ["refresh_rank_board"]
        elif not selected_matches_request:
            status = EvidenceStatus.conflict
            required_actions = ["rank.lookup"]
        elif selected_stale and not is_mixed_creation and not historical_range:
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
            selectedSources=contract_selected_sources,
            referenceSignals=reference_signals,
            warnings=warnings,
            selectedSnapshotGroup=selected_group,
            rejectedGroups=rejected_groups,
            requiredActions=required_actions,
            factualBoundary=(
                "selected historical snapshot groups within requested range only"
                if historical_range
                else "selected rank snapshot group only"
            ),
            inferenceBoundary=(
                "other snapshots are comparison signals within the requested historical range"
                if historical_range
                else "non-selected rank groups are reference signals, not latest facts"
            ),
        ), required, sources)

    def _has_broad_historical_rank_coverage(
        self,
        sources: list[KnowledgeSource],
        *,
        historical_range: bool,
        requested_channel_code: str | None,
        requested_board_code: str | None,
        requested_category: str | None,
        top_n: int,
    ) -> bool:
        if (
            not historical_range
            or any((requested_channel_code, requested_board_code, requested_category))
        ):
            return False
        unique_books = {
            source.bookId if source.bookId is not None else source.sourceRefId
            for source in sources
            if source.bookId is not None or source.sourceRefId is not None
        }
        board_scopes = {
            (
                self._normalize(source.channelCode),
                self._normalize(source.boardCode or source.category),
            )
            for source in sources
            if source.boardCode or source.category
        }
        required_scope_count = min(2, top_n)
        return len(unique_books) >= top_n and len(board_scopes) >= required_scope_count


    def commit(
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
        expected_project_id: int | None = None,
        allowed_project_work_scopes: set[tuple[int, int]] | None = None,
        claimed_citations: list[str] | tuple[str, ...] | set[str] | None = None,
        repair_already_used: bool = False,
        commit_id: str | None = None,
    ) -> EvidenceCommit:
        """Evaluate sources and wrap the result as the run's EvidenceCommit."""
        contract = self.evaluate(
            intent=intent,
            sources=sources,
            requested_platform=requested_platform,
            requested_channel_code=requested_channel_code,
            requested_board_code=requested_board_code,
            requested_category=requested_category,
            top_n=top_n,
            source_policy=source_policy,
            required_evidence=required_evidence,
        )
        return self.to_evidence_commit(
            contract,
            sources=sources,
            expected_project_id=expected_project_id,
            allowed_project_work_scopes=allowed_project_work_scopes,
            claimed_citations=claimed_citations,
            repair_already_used=repair_already_used,
            commit_id=commit_id,
            intent=intent,
        )

    def to_evidence_commit(
        self,
        contract: EvidenceContract,
        *,
        sources: list[KnowledgeSource] | None = None,
        expected_project_id: int | None = None,
        allowed_project_work_scopes: set[tuple[int, int]] | None = None,
        claimed_citations: list[str] | tuple[str, ...] | set[str] | None = None,
        repair_already_used: bool = False,
        commit_id: str | None = None,
        intent: str | None = None,
    ) -> EvidenceCommit:
        """Convert EvidenceContract (+ optional citation/project checks) into EvidenceCommit."""
        all_sources = list(sources if sources is not None else contract.selectedSources or [])
        selected_sources = list(contract.selectedSources or [])
        decisions: list[EvidenceDecision] = []
        reason_codes: list[str] = [f"contract_{contract.status.value}"]
        blocking_reject = False

        # Rank-oriented EvidenceContract may report missing when only chapter/project
        # sources exist. For non-market intents those sources remain committable.
        effective_contract = contract
        if (
            contract.status == EvidenceStatus.missing
            and all_sources
            and not self._is_pure_market_intent(intent)
            and not any((source.sourceType or "").upper() == "RANK" for source in all_sources)
        ):
            effective_contract = contract.model_copy(
                update={
                    "status": EvidenceStatus.verified_latest,
                    "selectedSources": list(all_sources),
                    "requiredActions": [],
                }
            )
            selected_sources = list(all_sources)
            reason_codes.append("non_rank_sources_accepted")

        base_state = self._decision_state_for_contract(effective_contract.status)
        contract = effective_contract
        selected_keys = {self._source_identity(source) for source in selected_sources}
        valid_citation_ids = self._valid_citation_ids(all_sources)
        allowed_scopes = {
            (int(project_id), int(work_id))
            for project_id, work_id in (allowed_project_work_scopes or set())
            if int(project_id) > 0 and int(work_id) > 0
        }

        for index, source in enumerate(all_sources, start=1):
            evidence_id = f"source:{index}"
            decision_state = base_state
            item_reasons: list[str] = []
            identity = self._source_identity(source)
            source_type = (source.sourceType or "").upper()

            if selected_sources and source_type == "RANK" and identity not in selected_keys:
                decision_state = EvidenceDecisionState.REJECTED
                item_reasons.append("not_selected_snapshot")

            if allowed_scopes and (source.projectId is not None or source.workId is not None):
                try:
                    source_scope = (int(source.projectId), int(source.workId))
                except (TypeError, ValueError):
                    source_scope = None
                if source_scope not in allowed_scopes:
                    decision_state = EvidenceDecisionState.REJECTED
                    item_reasons.append("cross_project_evidence")
                    blocking_reject = True
                    reason_codes.append("cross_project_evidence")
            elif expected_project_id is not None and source.projectId is not None:
                try:
                    if int(source.projectId) != int(expected_project_id):
                        decision_state = EvidenceDecisionState.REJECTED
                        item_reasons.append("cross_project_evidence")
                        blocking_reject = True
                        reason_codes.append("cross_project_evidence")
                except (TypeError, ValueError):
                    decision_state = EvidenceDecisionState.REJECTED
                    item_reasons.append("cross_project_evidence")
                    blocking_reject = True
                    reason_codes.append("cross_project_evidence")

            if source_type == "RANK" and contract.status == EvidenceStatus.stale:
                decision_state = EvidenceDecisionState.REJECTED
                item_reasons.append("stale_market_claim")
                blocking_reject = True
                reason_codes.append("stale_market_claim")

            if contract.status in {EvidenceStatus.missing, EvidenceStatus.conflict}:
                if source_type == "RANK" or not selected_sources:
                    decision_state = EvidenceDecisionState.REJECTED
                    item_reasons.append(f"contract_{contract.status.value}")
                    blocking_reject = True

            if decision_state is EvidenceDecisionState.ACCEPTED and not item_reasons:
                item_reasons.append("selected_evidence")
            elif decision_state is EvidenceDecisionState.DEGRADED and not item_reasons:
                item_reasons.append("directional_only")

            freshness = None
            if source.snapshotTime:
                freshness = "latest" if contract.status == EvidenceStatus.verified_latest else contract.status.value
            elif source_type == "RANK":
                freshness = contract.status.value

            decisions.append(
                EvidenceDecision(
                    evidenceId=evidence_id,
                    decision=decision_state,
                    freshness=freshness,
                    provenanceRef=(
                        f"snapshot:{source.snapshotId}"
                        if source.snapshotId is not None
                        else (f"project:{source.projectId}" if source.projectId is not None else None)
                    ),
                    citationRef=evidence_id,
                    reasonCodes=tuple(dict.fromkeys(item_reasons)),
                )
            )

        for citation in self._normalize_citations(claimed_citations):
            if citation in valid_citation_ids:
                continue
            decisions.append(
                EvidenceDecision(
                    evidenceId=f"citation:{citation}",
                    decision=EvidenceDecisionState.REJECTED,
                    citationRef=citation,
                    reasonCodes=("forged_citation",),
                )
            )
            blocking_reject = True
            reason_codes.append("forged_citation")

        if not decisions and contract.status == EvidenceStatus.missing:
            decisions.append(
                EvidenceDecision(
                    evidenceId="evidence:missing",
                    decision=EvidenceDecisionState.REJECTED,
                    reasonCodes=("missing_evidence",),
                )
            )
            blocking_reject = True
            reason_codes.append("missing_evidence")

        can_commit = (
            contract.status in {EvidenceStatus.verified_latest, EvidenceStatus.degraded_directional}
            and not blocking_reject
            and "forged_citation" not in reason_codes
        )
        repair_allowed = (
            (not can_commit)
            and (not repair_already_used)
            and (
                contract.status in {
                    EvidenceStatus.stale,
                    EvidenceStatus.missing,
                    EvidenceStatus.conflict,
                }
                or bool(contract.requiredActions)
            )
        )
        if repair_already_used:
            reason_codes.append("repair_budget_exhausted")
        if repair_allowed:
            reason_codes.append("targeted_repair_allowed")
        if can_commit:
            reason_codes.append("evidence_sufficient" if contract.status == EvidenceStatus.verified_latest else "directional_commit_allowed")
        else:
            reason_codes.append("commit_blocked")

        resolved_commit_id = (commit_id or "").strip() or self._default_commit_id(
            intent=intent,
            contract=contract,
            source_count=len(all_sources),
        )
        return EvidenceCommit(
            commitId=resolved_commit_id[:128],
            decisions=tuple(decisions),
            canCommit=can_commit,
            repairAllowed=repair_allowed,
            reasonCodes=tuple(dict.fromkeys(reason_codes)),
        )

    def _is_pure_market_intent(self, intent: str | None) -> bool:
        normalized = self._normalize(intent)
        return normalized in {
            "market_scan",
            "trend_research",
            "rank_research",
            "market",
            "trend",
        }

    def _decision_state_for_contract(self, status: EvidenceStatus) -> EvidenceDecisionState:
        if status == EvidenceStatus.verified_latest:
            return EvidenceDecisionState.ACCEPTED
        if status == EvidenceStatus.degraded_directional:
            return EvidenceDecisionState.DEGRADED
        return EvidenceDecisionState.REJECTED

    def _source_identity(self, source: KnowledgeSource) -> tuple[Any, ...]:
        return (
            (source.sourceType or "").upper(),
            source.bookId,
            source.snapshotId,
            source.snapshotTime,
            source.projectId,
            source.workId,
            source.chapterId,
            source.rankNo,
            source.retrievalBackend,
            source.title,
        )

    def _valid_citation_ids(self, sources: list[KnowledgeSource]) -> set[str]:
        valid: set[str] = set()
        for index, _source in enumerate(sources, start=1):
            valid.add(str(index))
            valid.add(f"source:{index}")
        return valid

    def _normalize_citations(
        self,
        claimed_citations: list[str] | tuple[str, ...] | set[str] | None,
    ) -> list[str]:
        if not claimed_citations:
            return []
        result: list[str] = []
        seen: set[str] = set()
        for raw in claimed_citations:
            value = str(raw or "").strip()
            if not value or value in seen:
                continue
            seen.add(value)
            result.append(value)
        return result

    def _default_commit_id(
        self,
        *,
        intent: str | None,
        contract: EvidenceContract,
        source_count: int,
    ) -> str:
        selected = contract.selectedSnapshotGroup
        snapshot_part = (
            f"{selected.snapshotId or selected.snapshotTime}"
            if selected is not None
            else "none"
        )
        raw = f"{intent or 'unknown'}|{contract.status.value}|{snapshot_part}|{source_count}"
        digest = re.sub(r"[^a-zA-Z0-9:_-]+", "", raw)[:96]
        return f"evidence:{digest or 'commit'}"

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
        if normalized in {
            "fresh_rank",
            "current_structured_rank_topn",
            "rank_evidence",
            "historical_rank_snapshot",
        }:
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
        if normalized == "project_bound_chapter_or_memory_evidence":
            return any(
                source.projectId is not None
                and source.workId is not None
                and (source.sourceType or "").upper().startswith("PROJECT_")
                for source in sources
            )
        return any(self._normalize(source.sourceType) == normalized for source in sources)

    def _requires_rank_evidence(self, requirements: list[str]) -> bool:
        return any(
            self._normalize(requirement) in {
                "fresh_rank",
                "current_structured_rank_topn",
                "rank_evidence",
                "historical_rank_snapshot",
            }
            for requirement in requirements
        )

    def _group_rank_sources(
        self,
        sources: list[KnowledgeSource],
        *,
        historical_range: bool = False,
    ) -> dict[str, list[KnowledgeSource]]:
        groups: dict[str, list[KnowledgeSource]] = {}
        for source in sources:
            key = self._group_key(source, historical_range=historical_range)
            groups.setdefault(key, []).append(source)
        return groups

    def _group_key(self, source: KnowledgeSource, *, historical_range: bool = False) -> str:
        if historical_range:
            parsed = self._parse_snapshot_time(source.snapshotTime or "")
            snapshot_marker = parsed.date().isoformat() if parsed is not None else source.snapshotTime
            return "|".join([
                self._clean(source.platform),
                self._clean(snapshot_marker),
                self._clean(source.retrievalBackend or "unknown_tool"),
            ])
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
            freshness=self._aggregate_freshness(sources),
            historicalReference=self._aggregate_historical(sources),
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
        historical_range: bool = False,
    ) -> list[EvidenceWarning]:
        warnings: list[EvidenceWarning] = []
        if rejected_groups:
            warnings.append(
                EvidenceWarning(
                    code=(
                        "historical_comparison_snapshots"
                        if historical_range
                        else "mixed_structured_rank_snapshot"
                    ),
                    message=(
                        "Multiple snapshots in the requested historical range are available for comparison."
                        if historical_range
                        else "Multiple structured rank snapshot groups were found; one group was selected for the answer."
                    ),
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
        if self._is_stale(selected_group) and not historical_range:
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


    def _aggregate_freshness(self, sources: list[KnowledgeSource]) -> str | None:
        states = []
        for source in sources:
            value = getattr(source, "freshness", None)
            if value:
                states.append(str(value).upper())
        if not states:
            return None
        if "EXPIRED" in states:
            return "EXPIRED"
        if "STALE" in states:
            return "STALE"
        if all(state == "FRESH" for state in states):
            return "FRESH"
        return states[0]

    def _aggregate_historical(self, sources: list[KnowledgeSource]) -> bool | None:
        flags = [getattr(source, "historicalReference", None) for source in sources]
        known = [flag for flag in flags if flag is not None]
        if not known:
            return None
        return any(bool(flag) for flag in known)

    def _is_stale(self, group: SnapshotGroup) -> bool:
        # Prefer Backend three-state freshness when present; Worker must not invent a second clock policy.
        state = str(getattr(group, "freshness", None) or "").upper()
        if state in {"EXPIRED", "STALE"}:
            return True
        if state == "FRESH":
            return False
        if getattr(group, "historicalReference", None) is True:
            return True
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
