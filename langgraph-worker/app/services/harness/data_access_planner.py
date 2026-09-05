from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date
from typing import Any

from pydantic import ValidationError

from .contracts import (
    CapabilityPlan,
    CapabilityScope,
    DataAccessFilter,
    DataAccessPlan,
    DataAccessPurpose,
    DataAccessRequest,
    DataEvidenceType,
    DataFilterField,
    DataProposalSource,
    DataRetrievalChannel,
    DatasetCapability,
    DataTemporalMode,
    DataTemporalScope,
    IntentEnvelope,
)


@dataclass(frozen=True, slots=True)
class _DatasetDefaults:
    purpose: DataAccessPurpose
    channels: tuple[DataRetrievalChannel, ...]
    evidence: tuple[DataEvidenceType, ...]
    max_limit: int
    requires_project_scope: bool = False


_DATASET_DEFAULTS: dict[DatasetCapability, _DatasetDefaults] = {
    DatasetCapability.MARKET_RANK: _DatasetDefaults(
        purpose=DataAccessPurpose.MARKET_CURRENT_STATE,
        channels=(DataRetrievalChannel.STRUCTURED,),
        evidence=(DataEvidenceType.CURRENT_RANK,),
        max_limit=100,
    ),
    DatasetCapability.MARKET_HISTORY: _DatasetDefaults(
        purpose=DataAccessPurpose.MARKET_HISTORY,
        channels=(
            DataRetrievalChannel.STRUCTURED,
            DataRetrievalChannel.FULLTEXT,
            DataRetrievalChannel.VECTOR,
        ),
        evidence=(
            DataEvidenceType.CURRENT_RANK,
            DataEvidenceType.HISTORICAL_SNAPSHOT,
        ),
        max_limit=60,
    ),
    DatasetCapability.BOOK_SOURCE: _DatasetDefaults(
        purpose=DataAccessPurpose.BOOK_ANALYSIS,
        channels=(
            DataRetrievalChannel.STRUCTURED,
            DataRetrievalChannel.FULLTEXT,
            DataRetrievalChannel.VECTOR,
        ),
        evidence=(DataEvidenceType.BOOK_SOURCE,),
        max_limit=20,
    ),
    DatasetCapability.PROJECT_KNOWLEDGE: _DatasetDefaults(
        purpose=DataAccessPurpose.PROJECT_RECALL,
        channels=(
            DataRetrievalChannel.STRUCTURED,
            DataRetrievalChannel.FULLTEXT,
            DataRetrievalChannel.VECTOR,
            DataRetrievalChannel.GRAPH,
        ),
        evidence=(
            DataEvidenceType.PROJECT_CHAPTER,
            DataEvidenceType.PROJECT_STRUCTURED_FACT,
        ),
        max_limit=20,
        requires_project_scope=True,
    ),
    DatasetCapability.PROJECT_CONTINUITY: _DatasetDefaults(
        purpose=DataAccessPurpose.PROJECT_CONTINUITY,
        channels=(
            DataRetrievalChannel.STRUCTURED,
            DataRetrievalChannel.FULLTEXT,
            DataRetrievalChannel.VECTOR,
            DataRetrievalChannel.GRAPH,
        ),
        evidence=(
            DataEvidenceType.PROJECT_CHAPTER,
            DataEvidenceType.PROJECT_STRUCTURED_FACT,
        ),
        max_limit=20,
        requires_project_scope=True,
    ),
    DatasetCapability.CONVERSATION_THREAD: _DatasetDefaults(
        purpose=DataAccessPurpose.FOLLOWUP_CONTEXT,
        channels=(),
        evidence=(DataEvidenceType.THREAD_CONTEXT,),
        max_limit=12,
    ),
}


_OPERATION_DATASETS: dict[str, tuple[DatasetCapability, ...]] = {
    "market_scan": (
        DatasetCapability.MARKET_RANK,
        DatasetCapability.MARKET_HISTORY,
    ),
    "market_research": (
        DatasetCapability.MARKET_RANK,
        DatasetCapability.MARKET_HISTORY,
    ),
    "mixed_creation_research": (
        DatasetCapability.MARKET_RANK,
        DatasetCapability.MARKET_HISTORY,
    ),
    "book_breakdown": (DatasetCapability.BOOK_SOURCE,),
    "project_knowledge": (
        DatasetCapability.PROJECT_KNOWLEDGE,
        DatasetCapability.PROJECT_CONTINUITY,
    ),
    "followup_context": (DatasetCapability.CONVERSATION_THREAD,),
}


_OPERATION_DEFAULT_DATASETS: dict[str, tuple[DatasetCapability, ...]] = {
    "market_scan": (DatasetCapability.MARKET_RANK,),
    "market_research": (
        DatasetCapability.MARKET_RANK,
        DatasetCapability.MARKET_HISTORY,
    ),
    "mixed_creation_research": (
        DatasetCapability.MARKET_RANK,
        DatasetCapability.MARKET_HISTORY,
    ),
    "book_breakdown": (DatasetCapability.BOOK_SOURCE,),
    "project_knowledge": (DatasetCapability.PROJECT_KNOWLEDGE,),
    "followup_context": (DatasetCapability.CONVERSATION_THREAD,),
}


_ENTITY_FILTER_FIELDS: tuple[tuple[str, DataFilterField], ...] = (
    ("platform", DataFilterField.PLATFORM),
    ("boardCode", DataFilterField.BOARD),
    ("board", DataFilterField.BOARD),
    ("category", DataFilterField.CATEGORY),
    ("bookName", DataFilterField.WORK_TITLE),
    ("workTitle", DataFilterField.WORK_TITLE),
    ("author", DataFilterField.AUTHOR),
    ("chapterFrom", DataFilterField.CHAPTER_FROM),
    ("chapterTo", DataFilterField.CHAPTER_TO),
)


_DATASET_ALLOWED_PURPOSES: dict[DatasetCapability, frozenset[DataAccessPurpose]] = {
    DatasetCapability.MARKET_RANK: frozenset({
        DataAccessPurpose.MARKET_CURRENT_STATE,
        DataAccessPurpose.MARKET_TAXONOMY,
        DataAccessPurpose.CREATIVE_CALIBRATION,
    }),
    DatasetCapability.MARKET_HISTORY: frozenset({
        DataAccessPurpose.MARKET_TAXONOMY,
        DataAccessPurpose.MARKET_HISTORY,
        DataAccessPurpose.CREATIVE_CALIBRATION,
    }),
    DatasetCapability.BOOK_SOURCE: frozenset({DataAccessPurpose.BOOK_ANALYSIS}),
    DatasetCapability.PROJECT_KNOWLEDGE: frozenset({
        DataAccessPurpose.PROJECT_RECALL,
        DataAccessPurpose.PROJECT_CONTINUITY,
    }),
    DatasetCapability.PROJECT_CONTINUITY: frozenset({DataAccessPurpose.PROJECT_CONTINUITY}),
    DatasetCapability.CONVERSATION_THREAD: frozenset({DataAccessPurpose.FOLLOWUP_CONTEXT}),
}


_DATASET_ALLOWED_FILTERS: dict[DatasetCapability, frozenset[DataFilterField]] = {
    DatasetCapability.MARKET_RANK: frozenset({
        DataFilterField.PLATFORM,
        DataFilterField.BOARD,
        DataFilterField.CATEGORY,
        DataFilterField.WORK_TITLE,
        DataFilterField.AUTHOR,
    }),
    DatasetCapability.MARKET_HISTORY: frozenset({
        DataFilterField.PLATFORM,
        DataFilterField.BOARD,
        DataFilterField.CATEGORY,
        DataFilterField.WORK_TITLE,
        DataFilterField.AUTHOR,
    }),
    DatasetCapability.BOOK_SOURCE: frozenset({
        DataFilterField.PLATFORM,
        DataFilterField.WORK_TITLE,
        DataFilterField.AUTHOR,
        DataFilterField.CHAPTER_FROM,
        DataFilterField.CHAPTER_TO,
    }),
    DatasetCapability.PROJECT_KNOWLEDGE: frozenset({
        DataFilterField.WORK_TITLE,
        DataFilterField.AUTHOR,
        DataFilterField.CHAPTER_FROM,
        DataFilterField.CHAPTER_TO,
        DataFilterField.EVIDENCE_STATUS,
    }),
    DatasetCapability.PROJECT_CONTINUITY: frozenset({
        DataFilterField.WORK_TITLE,
        DataFilterField.AUTHOR,
        DataFilterField.CHAPTER_FROM,
        DataFilterField.CHAPTER_TO,
        DataFilterField.EVIDENCE_STATUS,
    }),
    DatasetCapability.CONVERSATION_THREAD: frozenset(),
}


_SEMANTIC_ENTITY_KEYS = (
    "bookName",
    "workTitle",
    "author",
    "category",
    "currentTopic",
    "currentPremise",
    "constraints",
)


class DataAccessPlanner:
    """Convert non-authoritative intent semantics into a bounded data plan."""

    VERSION = "data-access-planner-v1"

    def plan(
        self,
        intent: IntentEnvelope,
        *,
        semantic_query: str,
        request_scope: CapabilityScope | None = None,
    ) -> DataAccessPlan:
        scope = request_scope or CapabilityScope()
        allowed = self._allowed_datasets(intent)
        raw_proposals = self._raw_proposals(intent.entities)
        rejected = max(0, len(raw_proposals) - 12)
        requests: list[DataAccessRequest] = []
        reasons: list[str] = [f"planner:{self.VERSION}"]

        for proposal in raw_proposals[:12]:
            request = self._request_from_proposal(
                proposal,
                intent=intent,
                semantic_query=semantic_query,
                allowed=allowed,
            )
            if request is None:
                rejected += 1
                continue
            requests.append(request)

        if requests:
            source = DataProposalSource.INTENT_ENTITIES
            reasons.append("accepted_intent_data_access_proposal")
        else:
            requests = self._default_requests(intent, semantic_query=semantic_query)
            if raw_proposals:
                source = DataProposalSource.DETERMINISTIC_FALLBACK
                reasons.append("invalid_intent_data_access_proposal")
            else:
                source = DataProposalSource.DETERMINISTIC_DEFAULT
                reasons.append("deterministic_data_access_default")

        if rejected:
            reasons.append("data_access_proposals_rejected")
        requires_project_scope = any(
            _DATASET_DEFAULTS[request.datasetCapability].requires_project_scope
            for request in requests
        )
        if requires_project_scope and scope.projectId is None:
            reasons.append("trusted_project_scope_required")
        if not requests:
            reasons.append("no_data_access_required")

        return DataAccessPlan(
            plannerVersion=self.VERSION,
            intentEnvelopeHash=intent.fingerprint,
            proposalSource=source,
            requests=tuple(requests),
            rejectedProposalCount=rejected,
            requiresTrustedProjectScope=requires_project_scope,
            reasonCodes=tuple(reasons),
        )

    @staticmethod
    def accepted_requests(
        plan: DataAccessPlan,
        capability_plan: CapabilityPlan,
        *,
        datasets: set[DatasetCapability] | frozenset[DatasetCapability] | None = None,
    ) -> tuple[DataAccessRequest, ...]:
        if capability_plan.dataAccessPlanHash != plan.fingerprint:
            return ()
        accepted_ids = set(capability_plan.dataAccessRequestIds)
        if not accepted_ids:
            return ()
        return tuple(
            request
            for request in plan.requests
            if request.requestId in accepted_ids
            and (datasets is None or request.datasetCapability in datasets)
        )

    def market_tool_constraints(
        self,
        plan: DataAccessPlan,
        capability_plan: CapabilityPlan,
    ) -> dict[str, Any]:
        if plan.proposalSource is not DataProposalSource.INTENT_ENTITIES:
            return {}
        requests = self.accepted_requests(
            plan,
            capability_plan,
            datasets={DatasetCapability.MARKET_RANK, DatasetCapability.MARKET_HISTORY},
        )
        if not requests:
            return {}
        selected = next(
            (
                request
                for request in requests
                if request.datasetCapability is DatasetCapability.MARKET_HISTORY
            ),
            requests[0],
        )
        constraints: dict[str, Any] = {"limit": selected.limit}
        field_targets = {
            DataFilterField.PLATFORM: "platform",
            DataFilterField.BOARD: "boardCode",
            DataFilterField.CATEGORY: "category",
        }
        for item in selected.filters:
            target = field_targets.get(item.field)
            if target and target not in constraints:
                constraints[target] = item.value

        temporal = selected.temporalScope
        source_policy: dict[str, Any] = {}
        if temporal.mode is DataTemporalMode.CURRENT:
            source_policy["allowHistorical"] = False
        else:
            source_policy.update({
                "freshness": "time_window",
                "allowHistorical": True,
                "requireSnapshotTime": True,
            })
            if temporal.mode is DataTemporalMode.LATEST_N_SNAPSHOTS:
                snapshot_count = max(1, min(12, int(temporal.latestNSnapshots or 1)))
                source_policy.update({
                    "snapshotCount": snapshot_count,
                    "requestedSnapshotCount": snapshot_count,
                    "timeWindowDays": max(30, min(365, snapshot_count * 14)),
                })
            elif temporal.mode is DataTemporalMode.RANGE:
                if temporal.startDate is not None and temporal.endDate is not None:
                    source_policy["snapshotStartDate"] = temporal.startDate.isoformat()
                    source_policy["snapshotEndDate"] = temporal.endDate.isoformat()
                    source_policy["timeWindowDays"] = max(
                        1,
                        min(365, (temporal.endDate - temporal.startDate).days + 1),
                    )
            elif temporal.mode is DataTemporalMode.AS_OF and temporal.asOfDate is not None:
                source_policy["timeWindowDays"] = max(
                    1,
                    min(365, (date.today() - temporal.asOfDate).days + 1),
                )
        constraints["sourcePolicy"] = source_policy
        return constraints

    def project_request(
        self,
        plan: DataAccessPlan,
        capability_plan: CapabilityPlan,
    ) -> DataAccessRequest | None:
        if plan.proposalSource is not DataProposalSource.INTENT_ENTITIES:
            return None
        requests = self.accepted_requests(
            plan,
            capability_plan,
            datasets={
                DatasetCapability.PROJECT_KNOWLEDGE,
                DatasetCapability.PROJECT_CONTINUITY,
            },
        )
        return next(
            (
                request
                for request in requests
                if request.datasetCapability is DatasetCapability.PROJECT_CONTINUITY
            ),
            requests[0] if requests else None,
        )

    @staticmethod
    def _raw_proposals(entities: Mapping[str, Any] | None) -> list[Mapping[str, Any]]:
        if not isinstance(entities, Mapping):
            return []
        raw = entities.get("dataAccess")
        if isinstance(raw, Mapping):
            return [raw]
        if not isinstance(raw, list):
            return []
        return [item for item in raw if isinstance(item, Mapping)]

    @staticmethod
    def _allowed_datasets(intent: IntentEnvelope) -> set[DatasetCapability]:
        allowed: set[DatasetCapability] = set()
        for operation in intent.operations:
            allowed.update(_OPERATION_DATASETS.get(operation, ()))
        return allowed

    def _request_from_proposal(
        self,
        proposal: Mapping[str, Any],
        *,
        intent: IntentEnvelope,
        semantic_query: str,
        allowed: set[DatasetCapability],
    ) -> DataAccessRequest | None:
        try:
            dataset = DatasetCapability(str(proposal.get("datasetCapability") or ""))
        except ValueError:
            return None
        if dataset not in allowed:
            return None
        defaults = _DATASET_DEFAULTS[dataset]
        payload = dict(proposal)
        payload["datasetCapability"] = dataset
        payload["semanticQuery"] = semantic_query
        payload.setdefault("purpose", self._purpose(intent, dataset, defaults.purpose))
        payload.setdefault("temporalScope", self._temporal_scope(intent.entities, dataset).model_dump(mode="json"))
        if not payload.get("retrievalChannels"):
            payload["retrievalChannels"] = defaults.channels
        if not payload.get("evidenceTypes"):
            payload["evidenceTypes"] = defaults.evidence
        proposal_filters = payload.get("filters")
        payload["filters"] = (
            self._filters_for_dataset(dataset, proposal_filters, reject_unsupported=True)
            if proposal_filters is not None
            else self._filters_for_dataset(dataset, self._filters(intent.entities))
        )
        if payload["filters"] is None:
            return None
        payload.setdefault("entities", self._entities(intent.entities))
        payload["limit"] = self._limit(payload.get("limit"), defaults.max_limit)
        payload.setdefault("required", True)
        try:
            purpose = DataAccessPurpose(str(payload.get("purpose") or ""))
            channels = tuple(DataRetrievalChannel(item) for item in payload["retrievalChannels"])
            evidence_types = tuple(DataEvidenceType(item) for item in payload["evidenceTypes"])
        except ValueError:
            return None
        if purpose not in _DATASET_ALLOWED_PURPOSES[dataset]:
            return None
        if any(channel not in defaults.channels for channel in channels):
            return None
        if any(evidence not in defaults.evidence for evidence in evidence_types):
            return None
        payload["purpose"] = purpose
        payload["retrievalChannels"] = channels
        payload["evidenceTypes"] = evidence_types
        payload["reasonCodes"] = (
            "planner:intent_entities",
            f"dataset:{dataset.value}",
            f"purpose:{purpose.value}",
        )
        try:
            return DataAccessRequest.model_validate(payload)
        except ValidationError:
            return None

    def _default_requests(
        self,
        intent: IntentEnvelope,
        *,
        semantic_query: str,
    ) -> list[DataAccessRequest]:
        datasets: list[DatasetCapability] = []
        for operation in intent.operations:
            for dataset in _OPERATION_DEFAULT_DATASETS.get(operation, ()):
                if dataset not in datasets:
                    datasets.append(dataset)
        requests: list[DataAccessRequest] = []
        for dataset in datasets:
            defaults = _DATASET_DEFAULTS[dataset]
            try:
                requests.append(DataAccessRequest(
                    datasetCapability=dataset,
                    purpose=self._purpose(intent, dataset, defaults.purpose),
                    semanticQuery=semantic_query,
                    entities=self._entities(intent.entities),
                    temporalScope=self._temporal_scope(intent.entities, dataset),
                    retrievalChannels=defaults.channels,
                    evidenceTypes=defaults.evidence,
                    filters=self._filters_for_dataset(dataset, self._filters(intent.entities)) or (),
                    limit=defaults.max_limit,
                    required=True,
                    reasonCodes=(
                        "planner:deterministic_default",
                        f"dataset:{dataset.value}",
                        f"purpose:{self._purpose(intent, dataset, defaults.purpose).value}",
                    ),
                ))
            except ValidationError:
                continue
        return requests

    @staticmethod
    def _purpose(
        intent: IntentEnvelope,
        dataset: DatasetCapability,
        default: DataAccessPurpose,
    ) -> DataAccessPurpose:
        question_type = str(intent.entities.get("marketQuestionType") or "").strip()
        if dataset in {DatasetCapability.MARKET_RANK, DatasetCapability.MARKET_HISTORY}:
            if question_type in {"taxonomy_absence", "taxonomy_classification", "derivative_genre"}:
                return DataAccessPurpose.MARKET_TAXONOMY
        return default

    @staticmethod
    def _temporal_scope(
        entities: Mapping[str, Any],
        dataset: DatasetCapability,
    ) -> DataTemporalScope:
        if dataset is DatasetCapability.MARKET_HISTORY:
            latest = DataAccessPlanner._int_value(entities.get("snapshotCount"))
            if latest is not None:
                return DataTemporalScope(
                    mode=DataTemporalMode.LATEST_N_SNAPSHOTS,
                    latestNSnapshots=max(1, min(12, latest)),
                )
            start = DataAccessPlanner._date_value(entities.get("startDate"))
            end = DataAccessPlanner._date_value(entities.get("endDate"))
            if start is not None and end is not None and start <= end:
                return DataTemporalScope(
                    mode=DataTemporalMode.RANGE,
                    startDate=start,
                    endDate=end,
                )
            as_of = DataAccessPlanner._date_value(entities.get("asOfDate"))
            if as_of is not None:
                return DataTemporalScope(mode=DataTemporalMode.AS_OF, asOfDate=as_of)
            return DataTemporalScope(
                mode=DataTemporalMode.LATEST_N_SNAPSHOTS,
                latestNSnapshots=6,
            )
        return DataTemporalScope()

    @staticmethod
    def _filters(entities: Mapping[str, Any]) -> tuple[DataAccessFilter, ...]:
        filters: list[DataAccessFilter] = []
        for key, field in _ENTITY_FILTER_FIELDS:
            value = entities.get(key)
            if value is None or isinstance(value, (dict, list, tuple, set)):
                continue
            try:
                filters.append(DataAccessFilter(field=field, value=value))
            except ValidationError:
                continue
        return tuple(filters[:12])

    @staticmethod
    def _filters_for_dataset(
        dataset: DatasetCapability,
        values: Any,
        *,
        reject_unsupported: bool = False,
    ) -> tuple[DataAccessFilter, ...] | None:
        if values is None:
            return ()
        allowed = _DATASET_ALLOWED_FILTERS[dataset]
        filters: list[DataAccessFilter] = []
        try:
            for item in values:
                model = item if isinstance(item, DataAccessFilter) else DataAccessFilter.model_validate(item)
                if model.field not in allowed:
                    if reject_unsupported:
                        return None
                    continue
                if model not in filters:
                    filters.append(model)
        except (TypeError, ValidationError):
            return None
        return tuple(filters[:12])

    @staticmethod
    def _entities(entities: Mapping[str, Any]) -> tuple[str, ...]:
        values: list[str] = []
        for key in _SEMANTIC_ENTITY_KEYS:
            value = entities.get(key)
            candidates = value if isinstance(value, list) else [value]
            for candidate in candidates:
                text = str(candidate or "").strip()
                if text and text not in values:
                    values.append(text)
                if len(values) >= 12:
                    return tuple(values)
        return tuple(values)

    @staticmethod
    def _limit(value: Any, maximum: int) -> int:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            parsed = maximum
        return max(1, min(maximum, parsed))

    @staticmethod
    def _int_value(value: Any) -> int | None:
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _date_value(value: Any) -> date | None:
        if isinstance(value, date):
            return value
        text = str(value or "").strip()
        if not text:
            return None
        try:
            return date.fromisoformat(text)
        except ValueError:
            return None


__all__ = ["DataAccessPlanner"]
