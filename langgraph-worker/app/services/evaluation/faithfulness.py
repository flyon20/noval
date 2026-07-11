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
        grounded_claims: list[str] | None = None,
    ) -> dict[str, Any]:
        failures: list[str] = []
        forbidden_claims = forbidden_claims or []
        for claim in forbidden_claims:
            if claim and claim in answer:
                failures.append(f"forbidden_claim:{claim}")

        grounded_claims = grounded_claims or []
        supported_claims = 0
        source_texts = [self._source_text(source) for source in sources]
        for claim in grounded_claims:
            claim_text = str(claim or "").strip()
            if not claim_text:
                continue
            if claim_text not in (answer or ""):
                failures.append(f"missing_grounded_claim:{claim_text}")
                continue
            if any(claim_text in source_text for source_text in source_texts):
                supported_claims += 1
            else:
                failures.append(f"unsupported_claim:{claim_text}")

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
            "grounded_claim_count": len([claim for claim in grounded_claims if str(claim or "").strip()]),
            "supported_claim_count": supported_claims,
            "claim_support_rate": supported_claims / max(1, len([claim for claim in grounded_claims if str(claim or "").strip()])),
        }

    def _source_text(self, source: KnowledgeSource) -> str:
        return " ".join(
            str(value or "")
            for value in (
                getattr(source, "bookName", None),
                getattr(source, "title", None),
                getattr(source, "preview", None),
                getattr(source, "analysisType", None),
                getattr(source, "category", None),
                getattr(source, "boardName", None),
            )
        )
