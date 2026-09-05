from __future__ import annotations

import json
from pathlib import Path

from app.services.evaluation.runner import (
    ProjectRetrievalCorpusRunner,
    run_project_retrieval_golden_fixture,
)


FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "project_retrieval_golden" / "v1"


def test_project_retrieval_golden_corpus_meets_release_scale() -> None:
    manifest = _read_json("manifest.json")
    cases = _read_json("cases.json")
    corpus = _read_json("corpus.json")

    assert manifest["publicSyntheticOnly"] is True
    assert int(manifest["caseCount"]) >= 1200
    assert len(cases) >= 1200
    assert len(manifest["books"]) >= 10
    assert {book["genre"] for book in manifest["books"]} >= {
        "urban", "xianxia", "system", "history", "romance",
    }
    assert all(100 <= int(book["chapterCount"]) <= 300 for book in manifest["books"])

    canonical = [document for document in corpus if document["documentRole"] == "canonical"]
    retired = [document for document in corpus if document["documentRole"] == "retired_decoy"]
    cross_user = [document for document in corpus if document["documentRole"] == "cross_user_decoy"]
    assert len(canonical) == 1500
    assert len(retired) == 1200
    assert len(cross_user) == 1200
    assert all(document["content"].strip() for document in canonical)
    assert all(document["generationStatus"] == "ACTIVE" for document in canonical)
    assert all(document["generationStatus"] == "RETIRED" for document in retired)
    assert all(int(document["userId"]) != 7 for document in cross_user)

    case_ids = [case["caseId"] for case in cases]
    assert len(case_ids) == len(set(case_ids))
    assert {case["evaluationCohort"]["intent"] for case in cases} == {
        "chapter_recall", "character_setting", "foreshadowing", "multi_hop",
    }
    fixture_text = json.dumps({"cases": cases, "corpus": corpus}, ensure_ascii=False)
    assert "CONFIRMED" not in fixture_text.upper()
    assert "PRIVATE_" not in fixture_text


def test_project_retrieval_golden_runner_executes_corpus_and_passes_release_gate() -> None:
    report = run_project_retrieval_golden_fixture(FIXTURE_DIR)
    committed_report = _read_json("release-gate-report.json")

    assert report == committed_report
    assert report["status"] == "passed"
    assert report["caseCount"] == 1200
    assert report["failedCaseCount"] == 0
    assert report["gate"]["passed"] is True
    assert report["gate"]["failures"] == []
    assert float(report["overall"]["metrics"]["recall_at_5"]) >= 0.95
    assert float(report["overall"]["metrics"]["chapter_location_accuracy"]) >= 0.95
    assert float(report["overall"]["metrics"]["structured_accuracy"]) >= 0.95
    assert float(report["overall"]["metrics"]["foreshadowing_coverage"]) >= 0.90
    assert float(report["overall"]["metrics"]["multi_hop_path_evidence"]) >= 0.85
    assert float(report["overall"]["metrics"]["cross_user_isolation_rate"]) == 1.0
    assert float(report["overall"]["metrics"]["old_generation_misretrieval_rate"]) < 0.01
    assert set(report["dimensions"]) == {"intent", "genre", "lengthBucket", "generation"}
    assert len(report["dimensions"]["intent"]) == 4
    assert len(report["dimensions"]["genre"]) == 5
    assert len(report["dimensions"]["lengthBucket"]) == 3
    assert len(report["dimensions"]["generation"]) == 10
    assert report["baselineComparison"]["baselineVersion"] == "v1"
    assert all(
        float(comparison["delta"]) >= -0.02
        for comparison in report["baselineComparison"]["metrics"].values()
    )


def test_project_retrieval_golden_runner_filters_retired_and_cross_user_decoys() -> None:
    corpus = _read_json("corpus.json")
    case = _read_json("cases.json")[0]
    results = ProjectRetrievalCorpusRunner(corpus).search(case)

    assert results
    assert results[0]["sourceId"] == case["relevantSourceIds"][0]
    assert all(document["generationStatus"] == "ACTIVE" for document in results)
    assert all(int(document["userId"]) == int(case["requestPayload"]["userId"]) for document in results)
    assert all(
        str(document["generationId"]) == str(case["requestPayload"]["generationId"])
        for document in results
    )


def test_project_retrieval_golden_runner_blocks_missing_active_corpus() -> None:
    manifest = _read_json("manifest.json")
    cases = _read_json("cases.json")[:40]
    corpus = _read_json("corpus.json")
    baseline = _read_json("baseline.json")
    blocked_case_ids = {case["caseId"] for case in cases}
    broken_corpus = [
        document
        for document in corpus
        if not (
            document["documentRole"] == "canonical"
            and f"{document['bookId']}-q{int(document['chapterNo']):03d}" in blocked_case_ids
        )
    ]

    report = ProjectRetrievalCorpusRunner(broken_corpus).run(
        cases,
        manifest=manifest,
        baseline=baseline,
    )

    assert report["status"] == "failed"
    assert report["failedCaseCount"] == len(cases)
    assert report["gate"]["passed"] is False
    assert any("recall_at_5" in failure for failure in report["gate"]["failures"])


def _read_json(name: str):
    return json.loads((FIXTURE_DIR / name).read_text(encoding="utf-8"))
