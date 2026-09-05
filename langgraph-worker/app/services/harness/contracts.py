from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Iterable, Mapping
from datetime import date
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .execution_path import ExecutionPath


_MAX_COLLECTION_ITEM_LENGTH = 256


class DomainStatus(StrEnum):
    IN_SCOPE = "IN_SCOPE"
    OUT_OF_SCOPE = "OUT_OF_SCOPE"
    NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION"


class SideEffectPolicy(StrEnum):
    READ_ONLY = "READ_ONLY"
    CANDIDATE_WRITE = "CANDIDATE_WRITE"
    CONFIRMED_WRITE = "CONFIRMED_WRITE"


class DatasetCapability(StrEnum):
    MARKET_RANK = "market.rank"
    MARKET_HISTORY = "market.history"
    BOOK_SOURCE = "book.source"
    PROJECT_KNOWLEDGE = "project.knowledge"
    PROJECT_CONTINUITY = "project.continuity"
    CONVERSATION_THREAD = "conversation.thread"


class DataAccessPurpose(StrEnum):
    MARKET_CURRENT_STATE = "market_current_state"
    MARKET_TAXONOMY = "market_taxonomy"
    MARKET_HISTORY = "market_history"
    CREATIVE_CALIBRATION = "creative_calibration"
    BOOK_ANALYSIS = "book_analysis"
    PROJECT_RECALL = "project_recall"
    PROJECT_CONTINUITY = "project_continuity"
    FOLLOWUP_CONTEXT = "followup_context"


class DataTemporalMode(StrEnum):
    CURRENT = "CURRENT"
    AS_OF = "AS_OF"
    RANGE = "RANGE"
    LATEST_N_SNAPSHOTS = "LATEST_N_SNAPSHOTS"


class DataRetrievalChannel(StrEnum):
    STRUCTURED = "structured"
    FULLTEXT = "fulltext"
    VECTOR = "vector"
    GRAPH = "graph"


class DataEvidenceType(StrEnum):
    CURRENT_RANK = "current_rank"
    HISTORICAL_SNAPSHOT = "historical_snapshot"
    BOOK_SOURCE = "book_source"
    PROJECT_CHAPTER = "project_chapter"
    PROJECT_STRUCTURED_FACT = "project_structured_fact"
    THREAD_CONTEXT = "thread_context"


class DataFilterField(StrEnum):
    PLATFORM = "platform"
    BOARD = "board"
    CATEGORY = "category"
    WORK_TITLE = "work_title"
    AUTHOR = "author"
    CHAPTER_FROM = "chapter_from"
    CHAPTER_TO = "chapter_to"
    EVIDENCE_STATUS = "evidence_status"


class DataProposalSource(StrEnum):
    INTENT_ENTITIES = "intent_entities"
    DETERMINISTIC_DEFAULT = "deterministic_default"
    DETERMINISTIC_FALLBACK = "deterministic_fallback"


class SkillUseState(StrEnum):
    CANDIDATE = "CANDIDATE"
    ELIGIBLE = "ELIGIBLE"
    ACTIVATED = "ACTIVATED"
    REJECTED = "REJECTED"
    VERIFIED = "VERIFIED"


class ExpertExecutionKind(StrEnum):
    INLINE = "INLINE"
    DETERMINISTIC = "DETERMINISTIC"
    DELEGATED = "DELEGATED"


class ExpertUseState(StrEnum):
    CANDIDATE = "CANDIDATE"
    ELIGIBLE = "ELIGIBLE"
    SELECTED = "SELECTED"
    EXECUTED = "EXECUTED"
    VERIFIED = "VERIFIED"
    SKIPPED = "SKIPPED"
    FAILED = "FAILED"
    DEGRADED = "DEGRADED"


class EvidenceDecisionState(StrEnum):
    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"
    DEGRADED = "DEGRADED"


class FrozenContract(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        str_strip_whitespace=True,
        validate_default=True,
    )


def _stable_unique_strings(values: Any) -> tuple[str, ...]:
    if values is None:
        return ()
    if isinstance(values, str):
        values = (values,)
    seen: set[str] = set()
    result: list[str] = []
    for item in values:
        value = str(item or "").strip()
        if not value or value in seen:
            continue
        if len(value) > _MAX_COLLECTION_ITEM_LENGTH:
            raise ValueError(
                f"collection items must not exceed {_MAX_COLLECTION_ITEM_LENGTH} characters"
            )
        seen.add(value)
        result.append(value)
    return tuple(result)


def _stable_unique_models(values: Any, key: str) -> tuple[Any, ...]:
    if values is None:
        return ()
    seen: set[str] = set()
    result: list[Any] = []
    for item in values:
        if isinstance(item, BaseModel):
            identifier = str(getattr(item, key, "") or "").strip()
        elif isinstance(item, Mapping):
            identifier = str(item.get(key) or "").strip()
        else:
            identifier = ""
        if not identifier or identifier in seen:
            continue
        seen.add(identifier)
        result.append(item)
    return tuple(result)


def _model_fingerprint(model: BaseModel) -> str:
    payload = model.model_dump(mode="json", exclude_none=True)
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return f"sha256:{hashlib.sha256(canonical.encode('utf-8')).hexdigest()}"


_SQL_TEXT_PATTERN = re.compile(
    r"(?:\bselect\b[\s\S]{0,160}\bfrom\b|"
    r"\binsert\b[\s\S]{0,80}\binto\b|"
    r"\bupdate\b[\s\S]{0,80}\bset\b|"
    r"\bdelete\b[\s\S]{0,80}\bfrom\b|"
    r"\b(?:drop|alter|create|truncate|grant|revoke)\b[\s\S]{0,80}\b(?:table|database|user|role)\b)",
    re.IGNORECASE,
)
_PATH_TEXT_PATTERN = re.compile(
    r"(?:\.\.[/\\]|[A-Za-z]:[/\\]|(?:^|\s)/(?:etc|var|home|root|proc|sys|dev)(?:[/\\]|$))",
    re.IGNORECASE,
)
_ENUM_FILTER_VALUE_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
_REASON_CODE_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")


def _safe_semantic_text(value: Any, *, field_name: str, max_length: int) -> str:
    text = str(value or "").strip()
    if not text:
        raise ValueError(f"{field_name} must not be empty")
    if len(text) > max_length:
        raise ValueError(f"{field_name} must not exceed {max_length} characters")
    if "://" in text or _PATH_TEXT_PATTERN.search(text):
        raise ValueError(f"{field_name} must not contain URLs or filesystem paths")
    if "--" in text or "/*" in text or "*/" in text or _SQL_TEXT_PATTERN.search(text):
        raise ValueError(f"{field_name} must not contain executable SQL")
    return text


def _stable_reason_codes(value: Any) -> tuple[str, ...]:
    values = _stable_unique_strings(value)
    for item in values:
        if not _REASON_CODE_PATTERN.fullmatch(item):
            raise ValueError("reason codes must be bounded identifiers")
    return values


def expert_bindings_hash(runtime_binding_fingerprints: Iterable[str]) -> str:
    fingerprints = []
    for item in runtime_binding_fingerprints:
        fingerprint = str(item or "").strip()
        if not fingerprint or len(fingerprint) > 128:
            raise ValueError("runtime binding fingerprints must be non-empty and bounded")
        fingerprints.append(fingerprint)
    canonical = json.dumps(sorted(fingerprints), ensure_ascii=False, separators=(",", ":"))
    return f"sha256:{hashlib.sha256(canonical.encode('utf-8')).hexdigest()}"


class IntentEnvelope(FrozenContract):
    contractVersion: str = "intent-envelope-v1"
    domainStatus: DomainStatus
    goal: str = Field(min_length=1, max_length=200)
    operations: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    entities: dict[str, Any] = Field(default_factory=dict)
    conversationMode: str = Field(default="new_question", min_length=1, max_length=64)
    constraints: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    ambiguity: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    missingSlots: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    classificationSource: str = Field(default="rules", min_length=1, max_length=64)
    notes: tuple[str, ...] = Field(default_factory=tuple, max_length=50)

    @field_validator("operations", "constraints", "ambiguity", "missingSlots", "notes", mode="before")
    @classmethod
    def deduplicate_string_collections(cls, value: Any) -> tuple[str, ...]:
        return _stable_unique_strings(value)

    @property
    def fingerprint(self) -> str:
        return _model_fingerprint(self)

    @property
    def envelopeId(self) -> str:
        return f"intent-{self.fingerprint.removeprefix('sha256:')[:16]}"

    def trace_summary(self) -> dict[str, Any]:
        return {
            "envelopeId": self.envelopeId,
            "fingerprint": self.fingerprint,
            "domainStatus": self.domainStatus.value,
            "classificationSource": self.classificationSource,
            "confidence": self.confidence,
            "conversationMode": self.conversationMode,
            "operationIds": list(self.operations),
            "reasonCodes": list(self.notes),
        }


class CapabilityRequest(FrozenContract):
    capabilityId: str = Field(min_length=1, max_length=128)
    required: bool = True
    reasonCodes: tuple[str, ...] = Field(default_factory=tuple, max_length=50)

    @field_validator("reasonCodes", mode="before")
    @classmethod
    def deduplicate_reason_codes(cls, value: Any) -> tuple[str, ...]:
        return _stable_unique_strings(value)


class CapabilityScope(FrozenContract):
    userId: int | str | None = None
    projectId: int | str | None = None
    bookId: int | str | None = None
    hasConversationContext: bool = False


class CapabilityLimits(FrozenContract):
    maxTurns: int | None = Field(default=None, ge=0)
    maxToolCalls: int | None = Field(default=None, ge=0)
    maxDelegations: int | None = Field(default=None, ge=0)
    maxInputTokens: int | None = Field(default=None, ge=0)


class DataTemporalScope(FrozenContract):
    mode: DataTemporalMode = DataTemporalMode.CURRENT
    asOfDate: date | None = None
    startDate: date | None = None
    endDate: date | None = None
    latestNSnapshots: int | None = Field(default=None, ge=1, le=12)

    @model_validator(mode="after")
    def validate_mode_fields(self) -> DataTemporalScope:
        if self.mode is DataTemporalMode.CURRENT:
            if any((self.asOfDate, self.startDate, self.endDate, self.latestNSnapshots)):
                raise ValueError("CURRENT temporal scope cannot carry historical fields")
        elif self.mode is DataTemporalMode.AS_OF:
            if self.asOfDate is None or any((self.startDate, self.endDate, self.latestNSnapshots)):
                raise ValueError("AS_OF temporal scope requires only asOfDate")
        elif self.mode is DataTemporalMode.RANGE:
            if self.startDate is None or self.endDate is None:
                raise ValueError("RANGE temporal scope requires startDate and endDate")
            if self.startDate > self.endDate:
                raise ValueError("RANGE startDate must not be after endDate")
            if self.asOfDate is not None or self.latestNSnapshots is not None:
                raise ValueError("RANGE temporal scope cannot carry asOfDate or latestNSnapshots")
        elif self.mode is DataTemporalMode.LATEST_N_SNAPSHOTS:
            if self.latestNSnapshots is None or any((self.asOfDate, self.startDate, self.endDate)):
                raise ValueError("LATEST_N_SNAPSHOTS requires only latestNSnapshots")
        return self


class DataAccessFilter(FrozenContract):
    field: DataFilterField
    value: str | int | float | bool

    @field_validator("value", mode="before")
    @classmethod
    def validate_filter_value(cls, value: Any) -> Any:
        if isinstance(value, bool):
            return value
        if isinstance(value, (int, float)):
            return value
        return _safe_semantic_text(value, field_name="filter value", max_length=256)

    @model_validator(mode="after")
    def validate_field_value(self) -> DataAccessFilter:
        if self.field in {
            DataFilterField.PLATFORM,
            DataFilterField.BOARD,
            DataFilterField.EVIDENCE_STATUS,
        }:
            if not isinstance(self.value, str) or not _ENUM_FILTER_VALUE_PATTERN.fullmatch(self.value):
                raise ValueError(f"{self.field.value} filter must be a bounded identifier")
        if self.field in {DataFilterField.CHAPTER_FROM, DataFilterField.CHAPTER_TO}:
            if isinstance(self.value, bool) or not isinstance(self.value, int):
                raise ValueError(f"{self.field.value} filter must be an integer")
            if self.value < 1 or self.value > 100_000:
                raise ValueError(f"{self.field.value} filter is outside the supported range")
        return self


class DataAccessRequest(FrozenContract):
    datasetCapability: DatasetCapability
    purpose: DataAccessPurpose
    semanticQuery: str = Field(min_length=1, max_length=2000)
    entities: tuple[str, ...] = Field(default_factory=tuple, max_length=12)
    temporalScope: DataTemporalScope = Field(default_factory=DataTemporalScope)
    retrievalChannels: tuple[DataRetrievalChannel, ...] = Field(default_factory=tuple, max_length=4)
    evidenceTypes: tuple[DataEvidenceType, ...] = Field(default_factory=tuple, max_length=8)
    filters: tuple[DataAccessFilter, ...] = Field(default_factory=tuple, max_length=12)
    limit: int = Field(default=20, ge=1, le=100)
    required: bool = True
    reasonCodes: tuple[str, ...] = Field(default_factory=tuple, max_length=20)

    @field_validator("semanticQuery", mode="before")
    @classmethod
    def validate_semantic_query(cls, value: Any) -> str:
        return _safe_semantic_text(value, field_name="semanticQuery", max_length=2000)

    @field_validator("entities", mode="before")
    @classmethod
    def validate_entities(cls, value: Any) -> tuple[str, ...]:
        values = _stable_unique_strings(value)
        return tuple(
            _safe_semantic_text(item, field_name="entity", max_length=256)
            for item in values
        )

    @field_validator("retrievalChannels", "evidenceTypes", mode="before")
    @classmethod
    def deduplicate_string_collections(cls, value: Any) -> tuple[Any, ...]:
        if value is None:
            return ()
        if isinstance(value, str):
            value = (value,)
        return tuple(dict.fromkeys(value))

    @field_validator("reasonCodes", mode="before")
    @classmethod
    def validate_reason_codes(cls, value: Any) -> tuple[str, ...]:
        return _stable_reason_codes(value)

    @field_validator("filters", mode="before")
    @classmethod
    def deduplicate_filters(cls, value: Any) -> tuple[Any, ...]:
        if value is None:
            return ()
        seen: set[str] = set()
        result: list[Any] = []
        for item in value:
            model = item if isinstance(item, DataAccessFilter) else DataAccessFilter.model_validate(item)
            key = json.dumps(model.model_dump(mode="json"), sort_keys=True, separators=(",", ":"))
            if key not in seen:
                seen.add(key)
                result.append(model)
        return tuple(result)

    @property
    def fingerprint(self) -> str:
        return _model_fingerprint(self)

    @property
    def requestId(self) -> str:
        return f"data-{self.fingerprint.removeprefix('sha256:')[:16]}"

    def trace_summary(self) -> dict[str, Any]:
        query_hash = hashlib.sha256(self.semanticQuery.encode("utf-8")).hexdigest()
        return {
            "requestId": self.requestId,
            "datasetCapability": self.datasetCapability.value,
            "purpose": self.purpose.value,
            "semanticQueryFingerprint": f"sha256:{query_hash}",
            "temporalMode": self.temporalScope.mode.value,
            "retrievalChannels": [item.value for item in self.retrievalChannels],
            "evidenceTypes": [item.value for item in self.evidenceTypes],
            "filterFields": [item.field.value for item in self.filters],
            "limit": self.limit,
            "required": self.required,
            "reasonCodes": list(self.reasonCodes),
        }


class DataAccessPlan(FrozenContract):
    contractVersion: str = "data-access-plan-v1"
    plannerVersion: str = "data-access-planner-v1"
    intentEnvelopeHash: str = Field(min_length=1, max_length=128)
    proposalSource: DataProposalSource = DataProposalSource.DETERMINISTIC_DEFAULT
    requests: tuple[DataAccessRequest, ...] = Field(default_factory=tuple, max_length=12)
    rejectedProposalCount: int = Field(default=0, ge=0, le=12)
    requiresTrustedProjectScope: bool = False
    reasonCodes: tuple[str, ...] = Field(default_factory=tuple, max_length=30)

    @field_validator("requests", mode="before")
    @classmethod
    def deduplicate_requests(cls, value: Any) -> tuple[DataAccessRequest, ...]:
        if value is None:
            return ()
        seen: set[str] = set()
        result: list[DataAccessRequest] = []
        for item in value:
            model = item if isinstance(item, DataAccessRequest) else DataAccessRequest.model_validate(item)
            if model.fingerprint not in seen:
                seen.add(model.fingerprint)
                result.append(model)
        return tuple(result)

    @field_validator("reasonCodes", mode="before")
    @classmethod
    def deduplicate_reason_codes(cls, value: Any) -> tuple[str, ...]:
        return _stable_reason_codes(value)

    @property
    def fingerprint(self) -> str:
        return _model_fingerprint(self)

    @property
    def planId(self) -> str:
        return f"data-plan-{self.fingerprint.removeprefix('sha256:')[:16]}"

    def trace_summary(self) -> dict[str, Any]:
        return {
            "planId": self.planId,
            "fingerprint": self.fingerprint,
            "intentEnvelopeHash": self.intentEnvelopeHash,
            "proposalSource": self.proposalSource.value,
            "requests": [request.trace_summary() for request in self.requests],
            "rejectedProposalCount": self.rejectedProposalCount,
            "requiresTrustedProjectScope": self.requiresTrustedProjectScope,
            "reasonCodes": list(self.reasonCodes),
        }


class CapabilityPlan(FrozenContract):
    contractVersion: str = "capability-plan-v1"
    compilerVersion: str = "capability-compiler-v1"
    intentEnvelopeHash: str = Field(min_length=1, max_length=128)
    capabilityRequests: tuple[CapabilityRequest, ...] = Field(default_factory=tuple, max_length=100)
    evidenceRequirements: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    retrievalScopes: tuple[str, ...] = Field(default_factory=tuple, max_length=20)
    memoryScopes: tuple[str, ...] = Field(default_factory=tuple, max_length=20)
    sideEffectPolicy: SideEffectPolicy = SideEffectPolicy.READ_ONLY
    executionPath: ExecutionPath = ExecutionPath.DIRECT
    limits: CapabilityLimits = Field(default_factory=CapabilityLimits)
    skillCandidateIds: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    expertCandidateIds: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    requestedToolCapabilities: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    dataAccessPlanHash: str | None = Field(default=None, max_length=128)
    dataAccessRequestIds: tuple[str, ...] = Field(default_factory=tuple, max_length=20)
    degradationPolicy: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    requiresProjectScope: bool = False
    delegationAllowed: bool = False
    reasonCodes: tuple[str, ...] = Field(default_factory=tuple, max_length=50)

    @field_validator("capabilityRequests", mode="before")
    @classmethod
    def deduplicate_capability_requests(cls, value: Any) -> tuple[Any, ...]:
        return _stable_unique_models(value, "capabilityId")

    @field_validator(
        "evidenceRequirements",
        "retrievalScopes",
        "memoryScopes",
        "skillCandidateIds",
        "expertCandidateIds",
        "requestedToolCapabilities",
        "dataAccessRequestIds",
        "degradationPolicy",
        "reasonCodes",
        mode="before",
    )
    @classmethod
    def deduplicate_string_collections(cls, value: Any) -> tuple[str, ...]:
        return _stable_unique_strings(value)

    @property
    def fingerprint(self) -> str:
        return _model_fingerprint(self)

    @property
    def planId(self) -> str:
        return f"plan-{self.fingerprint.removeprefix('sha256:')[:16]}"

    def trace_summary(self) -> dict[str, Any]:
        return {
            "planId": self.planId,
            "fingerprint": self.fingerprint,
            "intentEnvelopeHash": self.intentEnvelopeHash,
            "executionPath": self.executionPath.value,
            "capabilityIds": [request.capabilityId for request in self.capabilityRequests],
            "skillCandidateIds": list(self.skillCandidateIds),
            "expertCandidateIds": list(self.expertCandidateIds),
            "requestedToolCapabilityIds": list(self.requestedToolCapabilities),
            "dataAccessPlanHash": self.dataAccessPlanHash,
            "dataAccessRequestIds": list(self.dataAccessRequestIds),
            "reasonCodes": list(self.reasonCodes),
        }


class SkillUseRecord(FrozenContract):
    skillId: str = Field(min_length=1, max_length=128)
    version: str = Field(min_length=1, max_length=64)
    contentHash: str = Field(min_length=1, max_length=128)
    state: SkillUseState
    source: str | None = Field(default=None, max_length=64)
    provenanceRef: str | None = Field(default=None, max_length=256)
    candidateReasons: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    rejectionReasons: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    bodyInjected: bool = False
    materializedResourceIds: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    requestedCapabilityIds: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    authorizationDecisionId: str | None = Field(default=None, max_length=128)
    evidenceRefs: tuple[str, ...] = Field(default_factory=tuple, max_length=50)

    @field_validator(
        "candidateReasons",
        "rejectionReasons",
        "materializedResourceIds",
        "requestedCapabilityIds",
        "evidenceRefs",
        mode="before",
    )
    @classmethod
    def deduplicate_string_collections(cls, value: Any) -> tuple[str, ...]:
        return _stable_unique_strings(value)


class ExpertBinding(FrozenContract):
    bindingId: str = Field(min_length=1, max_length=128)
    expertId: str = Field(min_length=1, max_length=128)
    profileVersion: str | None = Field(default=None, max_length=64)
    evalConfigFingerprint: str = Field(min_length=1, max_length=128)
    runtimeBindingFingerprint: str = Field(min_length=1, max_length=128)
    executionKind: ExpertExecutionKind
    capabilityIds: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    requestedToolCapabilityIds: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    reasonCodes: tuple[str, ...] = Field(default_factory=tuple, max_length=50)

    @field_validator("capabilityIds", "requestedToolCapabilityIds", "reasonCodes", mode="before")
    @classmethod
    def deduplicate_string_collections(cls, value: Any) -> tuple[str, ...]:
        return _stable_unique_strings(value)


class ExpertUseRecord(FrozenContract):
    bindingId: str = Field(min_length=1, max_length=128)
    expertId: str = Field(min_length=1, max_length=128)
    executionKind: ExpertExecutionKind
    state: ExpertUseState
    evidenceRefs: tuple[str, ...] = Field(default_factory=tuple, max_length=50)
    reasonCodes: tuple[str, ...] = Field(default_factory=tuple, max_length=50)

    @field_validator("evidenceRefs", "reasonCodes", mode="before")
    @classmethod
    def deduplicate_string_collections(cls, value: Any) -> tuple[str, ...]:
        return _stable_unique_strings(value)


class ToolGrant(FrozenContract):
    grantId: str = Field(min_length=1, max_length=128)
    capabilityId: str = Field(min_length=1, max_length=128)
    toolName: str = Field(min_length=1, max_length=128)
    route: str = Field(min_length=1, max_length=128)
    scope: str = Field(min_length=1, max_length=64)
    sideEffectPolicy: SideEffectPolicy = SideEffectPolicy.READ_ONLY
    timeoutMs: int | None = Field(default=None, ge=1)
    idempotent: bool = True
    maxCalls: int | None = Field(default=None, ge=0)
    reasonCodes: tuple[str, ...] = Field(default_factory=tuple, max_length=50)

    @field_validator("reasonCodes", mode="before")
    @classmethod
    def deduplicate_reason_codes(cls, value: Any) -> tuple[str, ...]:
        return _stable_unique_strings(value)


class AuthorizationDecision(FrozenContract):
    decisionId: str = Field(min_length=1, max_length=128)
    grants: tuple[ToolGrant, ...] = Field(default_factory=tuple, max_length=100)
    deniedCapabilityIds: tuple[str, ...] = Field(default_factory=tuple, max_length=100)
    reasonCodes: tuple[str, ...] = Field(default_factory=tuple, max_length=50)

    @field_validator("grants", mode="before")
    @classmethod
    def deduplicate_grants(cls, value: Any) -> tuple[Any, ...]:
        return _stable_unique_models(value, "grantId")

    @field_validator("deniedCapabilityIds", "reasonCodes", mode="before")
    @classmethod
    def deduplicate_string_collections(cls, value: Any) -> tuple[str, ...]:
        return _stable_unique_strings(value)


class EvidenceDecision(FrozenContract):
    evidenceId: str = Field(min_length=1, max_length=128)
    decision: EvidenceDecisionState
    freshness: str | None = Field(default=None, max_length=64)
    provenanceRef: str | None = Field(default=None, max_length=256)
    citationRef: str | None = Field(default=None, max_length=256)
    reasonCodes: tuple[str, ...] = Field(default_factory=tuple, max_length=50)

    @field_validator("reasonCodes", mode="before")
    @classmethod
    def deduplicate_reason_codes(cls, value: Any) -> tuple[str, ...]:
        return _stable_unique_strings(value)


class EvidenceCommit(FrozenContract):
    commitId: str = Field(min_length=1, max_length=128)
    decisions: tuple[EvidenceDecision, ...] = Field(default_factory=tuple, max_length=100)
    canCommit: bool
    repairAllowed: bool = False
    reasonCodes: tuple[str, ...] = Field(default_factory=tuple, max_length=50)

    @field_validator("decisions", mode="before")
    @classmethod
    def deduplicate_decisions(cls, value: Any) -> tuple[Any, ...]:
        return _stable_unique_models(value, "evidenceId")

    @field_validator("reasonCodes", mode="before")
    @classmethod
    def deduplicate_reason_codes(cls, value: Any) -> tuple[str, ...]:
        return _stable_unique_strings(value)

    def trace_summary(self) -> dict[str, Any]:
        return {
            "commitId": self.commitId,
            "evidenceIds": [decision.evidenceId for decision in self.decisions],
            "canCommit": self.canCommit,
            "repairAllowed": self.repairAllowed,
            "reasonCodes": list(self.reasonCodes),
        }


class HarnessRunFingerprint(FrozenContract):
    modelName: str = Field(min_length=1, max_length=128)
    harnessVersion: str = Field(min_length=1, max_length=128)
    compilerVersion: str = Field(min_length=1, max_length=128)
    skillBomHash: str = Field(min_length=1, max_length=128)
    expertBindingsHash: str = Field(min_length=1, max_length=128)
    toolManifestVersion: str = Field(min_length=1, max_length=128)

    @property
    def fingerprint(self) -> str:
        return _model_fingerprint(self)


__all__ = [
    "AuthorizationDecision",
    "CapabilityLimits",
    "CapabilityPlan",
    "CapabilityRequest",
    "CapabilityScope",
    "DataAccessFilter",
    "DataAccessPlan",
    "DataAccessPurpose",
    "DataAccessRequest",
    "DataEvidenceType",
    "DataFilterField",
    "DataProposalSource",
    "DataRetrievalChannel",
    "DatasetCapability",
    "DataTemporalMode",
    "DataTemporalScope",
    "DomainStatus",
    "EvidenceCommit",
    "EvidenceDecision",
    "EvidenceDecisionState",
    "ExpertBinding",
    "ExpertExecutionKind",
    "ExpertUseRecord",
    "ExpertUseState",
    "HarnessRunFingerprint",
    "IntentEnvelope",
    "SideEffectPolicy",
    "SkillUseRecord",
    "SkillUseState",
    "ToolGrant",
    "expert_bindings_hash",
]
