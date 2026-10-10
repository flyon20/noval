from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Iterable, Mapping

from .trust import TrustEnvelope


_CITATION_PATTERN = re.compile(r"\[([A-Za-z0-9_.:-]+)]")
_INJECTION_PATTERNS = {
    "instruction_override": (
        re.compile(r"\bignore\b.{0,40}\b(?:previous|prior|above|system|developer)\b.{0,20}\binstructions?\b", re.I),
        re.compile(r"\b(?:disregard|override|bypass)\b.{0,40}\b(?:instructions?|rules?|policy|policies)\b", re.I),
        re.compile(r"忽略.{0,20}(?:之前|以上|系统).{0,20}(?:指令|规则)"),
    ),
    "tool_execution": (
        re.compile(r"\b(?:call|invoke|run|execute|use)\b.{0,40}\b(?:tool|function|command)\b", re.I),
        re.compile(r"(?:调用|执行|运行).{0,20}(?:工具|函数|命令)"),
    ),
    "cross_project_access": (
        re.compile(r"\b(?:another|other|different|cross[- ]project)\b.{0,30}\bproject\b", re.I),
        re.compile(r"(?:其他|另一个|跨).{0,12}项目"),
    ),
    "forged_authority": (
        re.compile(r"(?:<\/?(?:system|developer)>|\b(?:system|developer)\s+message\b|\byou are now\b)", re.I),
        re.compile(r"(?:系统|开发者)消息"),
    ),
    "secret_exfiltration": (
        re.compile(r"\b(?:reveal|show|print|leak|expose)\b.{0,40}\b(?:system prompt|developer prompt|secret|token|credential|private key)\b", re.I),
        re.compile(r"(?:泄露|显示|输出).{0,20}(?:提示词|密钥|令牌|凭据)"),
    ),
}


@dataclass(frozen=True, slots=True)
class ValidationResult:
    valid: bool
    reason: str = "ok"
    details: Mapping[str, Any] = field(default_factory=dict)

    @property
    def is_valid(self) -> bool:
        return self.valid

    def __bool__(self) -> bool:
        return self.valid


class ProjectScopeValidator:
    def validate(
        self,
        *,
        user_id: str | None = None,
        project_id: str | None = None,
        expected_user_id: str,
        expected_project_id: str | None,
        actual_user_id: str | None = None,
        actual_project_id: str | None = None,
    ) -> ValidationResult:
        actual_user = _normalize_identifier(actual_user_id if actual_user_id is not None else user_id)
        actual_project = _normalize_identifier(
            actual_project_id if actual_project_id is not None else project_id
        )
        expected_user = _normalize_identifier(expected_user_id)
        expected_project = _normalize_identifier(expected_project_id)

        if not expected_user:
            return ValidationResult(False, "expected_user_scope_missing")
        if actual_user != expected_user:
            return ValidationResult(
                False,
                "user_scope_mismatch",
                {"actual_user_id": actual_user, "expected_user_id": expected_user},
            )
        if actual_project != expected_project:
            return ValidationResult(
                False,
                "project_scope_mismatch",
                {
                    "actual_project_id": actual_project,
                    "expected_project_id": expected_project,
                },
            )
        return ValidationResult(True)

    def validate_envelope(
        self,
        envelope: TrustEnvelope[Any],
        *,
        expected_user_id: str,
        expected_project_id: str | None,
    ) -> ValidationResult:
        return self.validate(
            user_id=envelope.user_id,
            project_id=envelope.project_id,
            expected_user_id=expected_user_id,
            expected_project_id=expected_project_id,
        )


class PromptInjectionValidator:
    def validate(self, content: str) -> ValidationResult:
        text = str(content or "")
        signals = sorted(
            signal
            for signal, patterns in _INJECTION_PATTERNS.items()
            if any(pattern.search(text) for pattern in patterns)
        )
        if signals:
            return ValidationResult(
                False,
                "prompt_injection_detected",
                {"signals": signals},
            )
        return ValidationResult(True)

    def is_injection(self, content: str) -> bool:
        return not self.validate(content).valid


class DomainPolicyValidator:
    def __init__(self, allowed_domains: Iterable[str]) -> None:
        if isinstance(allowed_domains, str):
            allowed_domains = (allowed_domains,)
        self._allowed_domains = frozenset(
            normalized
            for domain in allowed_domains
            if (normalized := _normalize_domain(domain))
        )
        if not self._allowed_domains:
            raise ValueError("allowed_domains must not be empty")

    @property
    def allowed_domains(self) -> frozenset[str]:
        return self._allowed_domains

    def validate(self, domain: str | None) -> ValidationResult:
        normalized = _normalize_domain(domain)
        if not normalized:
            return ValidationResult(False, "domain_missing")
        if normalized not in self._allowed_domains:
            return ValidationResult(
                False,
                "domain_not_allowed",
                {"domain": normalized, "allowed_domains": sorted(self._allowed_domains)},
            )
        return ValidationResult(True, details={"domain": normalized})


class EvidenceCitationValidator:
    def __init__(self, *, require_citations: bool = False) -> None:
        self._require_citations = bool(require_citations)

    def validate(
        self,
        answer: str | None,
        sources: Iterable[Mapping[str, Any] | Any],
        *,
        citation_ids: Iterable[str | int] | None = None,
        expected_user_id: str | None = None,
        expected_project_id: str | None = None,
    ) -> ValidationResult:
        citations = _unique_in_order(
            _normalize_citation_id(value)
            for value in (
                citation_ids
                if citation_ids is not None
                else _CITATION_PATTERN.findall(str(answer or ""))
            )
        )
        citations = [citation for citation in citations if citation]
        if not citations:
            if self._require_citations:
                return ValidationResult(False, "missing_citation")
            return ValidationResult(True)

        source_index = _index_sources(list(sources))
        forged = [citation for citation in citations if citation not in source_index]
        if forged:
            return ValidationResult(False, "forged_citation", {"citation_ids": forged})

        expected_user = _normalize_identifier(expected_user_id)
        expected_project = _normalize_identifier(expected_project_id)
        for citation in citations:
            source = source_index[citation]
            source_user = _source_scope(source, "user")
            source_project = _source_scope(source, "project")
            if expected_user is not None and source_user != expected_user:
                return ValidationResult(
                    False,
                    "source_user_scope_mismatch",
                    {
                        "citation_id": citation,
                        "actual_user_id": source_user,
                        "expected_user_id": expected_user,
                    },
                )
            if expected_project is not None and source_project != expected_project:
                return ValidationResult(
                    False,
                    "source_project_scope_mismatch",
                    {
                        "citation_id": citation,
                        "actual_project_id": source_project,
                        "expected_project_id": expected_project,
                    },
                )
        return ValidationResult(True, details={"citation_ids": citations})


DomainBoundaryValidator = DomainPolicyValidator
ScopeValidator = ProjectScopeValidator


def validate_scope(
    *,
    user_id: str | None,
    project_id: str | None,
    expected_user_id: str,
    expected_project_id: str | None,
) -> ValidationResult:
    return ProjectScopeValidator().validate(
        user_id=user_id,
        project_id=project_id,
        expected_user_id=expected_user_id,
        expected_project_id=expected_project_id,
    )


def validate_citations(
    answer: str | None,
    sources: Iterable[Mapping[str, Any] | Any],
    *,
    expected_user_id: str | None = None,
    expected_project_id: str | None = None,
    require_citations: bool = False,
) -> ValidationResult:
    return EvidenceCitationValidator(require_citations=require_citations).validate(
        answer,
        sources,
        expected_user_id=expected_user_id,
        expected_project_id=expected_project_id,
    )


def _normalize_identifier(value: Any) -> str | None:
    if value is None:
        return None
    normalized = str(value).strip()
    return normalized or None


def _normalize_domain(value: Any) -> str | None:
    normalized = _normalize_identifier(value)
    if normalized is None:
        return None
    return re.sub(r"[\s_]+", "-", normalized.lower())


def _normalize_citation_id(value: Any) -> str:
    return str(value).strip().removeprefix("[").removesuffix("]").strip()


def _unique_in_order(values: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    unique: list[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            unique.append(value)
    return unique


def _index_sources(sources: list[Mapping[str, Any] | Any]) -> dict[str, Mapping[str, Any] | Any]:
    indexed: dict[str, Mapping[str, Any] | Any] = {}
    for position, source in enumerate(sources, start=1):
        identifiers = {
            str(position),
            *(
                normalized
                for field_name in ("citation_label", "evidence_id", "source_id", "id")
                if (normalized := _normalize_citation_id(_read_field(source, field_name)))
            ),
        }
        for identifier in identifiers:
            indexed.setdefault(identifier, source)
    return indexed


def _source_scope(source: Mapping[str, Any] | Any, scope_name: str) -> str | None:
    direct = _read_field(source, f"{scope_name}_id")
    if direct is None:
        direct = _read_field(source, f"{scope_name}Id")
    if direct is None:
        scope = _read_field(source, "scope")
        direct = _read_field(scope, f"{scope_name}_id")
        if direct is None:
            direct = _read_field(scope, f"{scope_name}Id")
    return _normalize_identifier(direct)


def _read_field(value: Any, field_name: str) -> Any:
    if isinstance(value, Mapping):
        return value.get(field_name)
    return getattr(value, field_name, None)
