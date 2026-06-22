from __future__ import annotations

import re
from typing import Any

from app.models.knowledge import KnowledgeSource


class RuleBasedFaithfulnessEvaluator:
    """Cheap local gate before optional LLM-as-judge evaluation."""

    _citation_pattern = re.compile(r"\[(\d+)]")

    def evaluate(
        self,
        *,
        answer: str,
        sources: list[KnowledgeSource],
        forbidden_claims: list[str] | None = None,
    ) -> dict[str, Any]:
        failures: list[str] = []
        forbidden_claims = forbidden_claims or []
        for claim in forbidden_claims:
            if claim and claim in answer:
                failures.append(f"forbidden_claim:{claim}")

        citations = [int(match.group(1)) for match in self._citation_pattern.finditer(answer or "")]
        valid_citations = [citation for citation in citations if 1 <= citation <= len(sources)]
        invalid_citations = [citation for citation in citations if citation not in valid_citations]
        for citation in invalid_citations:
            failures.append(f"invalid_citation:[{citation}]")

        citation_precision = len(valid_citations) / max(1, len(citations))
        return {
            "passed": not failures,
            "failures": failures,
            "citation_count": len(citations),
            "valid_citation_count": len(valid_citations),
            "citation_precision": citation_precision,
        }
