from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "tests/fixtures/project_retrieval_golden/v1"
GENRES = ("urban", "xianxia", "system", "history", "romance")
INTENTS = ("chapter_recall", "character_setting", "foreshadowing", "multi_hop")
LENGTH_BUCKETS = ("short", "mid", "long")
CHAPTERS_PER_BOOK = 150
CASES_PER_BOOK = 120
OWNER_USER_ID = 7
DECOY_USER_ID = 8


def main() -> None:
    books: list[dict] = []
    cases: list[dict] = []
    corpus: list[dict] = []
    for genre_index, genre in enumerate(GENRES):
        for book_index in range(2):
            book_number = book_index + 1
            book_id = f"book-{genre}-{book_number}"
            project_id = 900 + genre_index
            work_id = 1000 + book_index
            generation_id = 77 + genre_index * 2 + book_index
            books.append({
                "bookId": book_id,
                "genre": genre,
                "title": f"Synthetic {genre.title()} Tale {book_number}",
                "projectId": project_id,
                "workId": work_id,
                "activeGenerationId": generation_id,
                "chapterCount": CHAPTERS_PER_BOOK,
                "publicSynthetic": True,
                "notes": "Self-authored synthetic test text only; no private user content.",
            })
            for chapter_number in range(1, CHAPTERS_PER_BOOK + 1):
                chapter = _canonical_chapter(
                    genre=genre,
                    book_id=book_id,
                    project_id=project_id,
                    work_id=work_id,
                    generation_id=generation_id,
                    chapter_number=chapter_number,
                )
                corpus.append(chapter)
                if chapter_number <= CASES_PER_BOOK:
                    corpus.append(_retired_decoy(chapter))
                    corpus.append(_cross_user_decoy(chapter))
                    cases.append(_case_for(chapter))

    assert len(books) == 10
    assert len(cases) == 1200
    assert sum(1 for document in corpus if document["documentRole"] == "canonical") == 1500
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = {
        "corpusVersion": "v1",
        "description": "Executable synthetic project retrieval corpus and release gate.",
        "books": books,
        "caseCount": len(cases),
        "targetCaseCount": 1200,
        "documentCount": len(corpus),
        "chapterCountMinimum": CHAPTERS_PER_BOOK,
        "chapterCountMaximum": CHAPTERS_PER_BOOK,
        "publicSyntheticOnly": True,
        "genres": list(GENRES),
        "intents": list(INTENTS),
        "lengthBuckets": list(LENGTH_BUCKETS),
        "casesFile": "cases.json",
        "corpusFile": "corpus.json",
        "baselineFile": "baseline.json",
        "releaseReportFile": "release-gate-report.json",
    }
    _write_json(OUT / "manifest.json", manifest)
    _write_json(OUT / "cases.json", cases)
    _write_json(OUT / "corpus.json", corpus)
    print(f"wrote {len(corpus)} documents and {len(cases)} cases to {OUT}")


def _canonical_chapter(
    *,
    genre: str,
    book_id: str,
    project_id: int,
    work_id: int,
    generation_id: int,
    chapter_number: int,
) -> dict:
    marker = _marker(book_id, chapter_number)
    length_bucket = LENGTH_BUCKETS[(chapter_number - 1) % len(LENGTH_BUCKETS)]
    seed_number = ((chapter_number - 1) % 10) + 1
    rule_number = ((chapter_number - 1) % 5) + 1
    hero_status = "injured" if chapter_number % 2 == 1 else "stable"
    foreshadowing_id = f"foreshadowing:{genre}-seed-{seed_number}"
    source_id = f"chapter:{chapter_number}"
    edge_id = f"edge:{book_id}-{chapter_number}-{((chapter_number - 1) % 3) + 1}"
    filler_count = {"short": 1, "mid": 4, "long": 8}[length_bucket]
    core = (
        f"{marker} identifies chapter {chapter_number} of {book_id}. "
        f"The hero status is {hero_status}. The {genre} setting follows rule-{rule_number}. "
        f"The chapter plants {foreshadowing_id} and records evidence path {edge_id}. "
    )
    filler = " ".join(
        f"Synthetic scene {scene_number} tests scoped retrieval without copying published fiction."
        for scene_number in range(1, filler_count + 1)
    )
    content = f"{core}{filler}"
    return {
        "documentId": f"{book_id}:chapter:{chapter_number}:generation:{generation_id}:user:{OWNER_USER_ID}",
        "documentRole": "canonical",
        "sourceId": source_id,
        "bookId": book_id,
        "genre": genre,
        "userId": OWNER_USER_ID,
        "projectId": project_id,
        "workId": work_id,
        "chapterId": chapter_number,
        "chapterNo": chapter_number,
        "generationId": generation_id,
        "generationStatus": "ACTIVE",
        "visibility": "private",
        "lengthBucket": length_bucket,
        "title": f"Chapter {chapter_number}: {marker}",
        "content": content,
        "contentHash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        "foreshadowingIds": [foreshadowing_id],
        "structuredValues": {
            "character:hero:status": hero_status,
            f"setting:{genre}:rule": f"rule-{rule_number}",
        },
        "pathEdges": {edge_id: [source_id, foreshadowing_id]},
    }


def _retired_decoy(chapter: dict) -> dict:
    marker = _marker(str(chapter["bookId"]), int(chapter["chapterNo"]))
    content = f"{marker} {marker} {marker} obsolete retired generation decoy"
    return {
        **chapter,
        "documentId": f"{chapter['bookId']}:chapter:{chapter['chapterNo']}:generation:{int(chapter['generationId']) - 1}:user:{OWNER_USER_ID}",
        "documentRole": "retired_decoy",
        "generationId": int(chapter["generationId"]) - 1,
        "generationStatus": "RETIRED",
        "title": f"Retired {chapter['title']}",
        "content": content,
        "contentHash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
    }


def _cross_user_decoy(chapter: dict) -> dict:
    marker = _marker(str(chapter["bookId"]), int(chapter["chapterNo"]))
    content = f"{marker} {marker} {marker} cross-user isolation decoy"
    return {
        **chapter,
        "documentId": f"{chapter['bookId']}:chapter:{chapter['chapterNo']}:generation:{chapter['generationId']}:user:{DECOY_USER_ID}",
        "documentRole": "cross_user_decoy",
        "userId": DECOY_USER_ID,
        "title": f"Cross-user {chapter['title']}",
        "content": content,
        "contentHash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
    }


def _case_for(chapter: dict) -> dict:
    chapter_number = int(chapter["chapterNo"])
    marker = _marker(str(chapter["bookId"]), chapter_number)
    intent = INTENTS[(chapter_number - 1) % len(INTENTS)]
    foreshadowing_id = str(chapter["foreshadowingIds"][0])
    edge_id = next(iter(chapter["pathEdges"]))
    questions = {
        "chapter_recall": f"Which chapter in {chapter['bookId']} contains clue {marker}?",
        "character_setting": f"For clue {marker}, retrieve the hero status and setting rule.",
        "foreshadowing": f"Where does clue {marker} plant {foreshadowing_id}?",
        "multi_hop": f"Which evidence path connects clue {marker} to {foreshadowing_id}?",
    }
    return {
        "caseId": f"{chapter['bookId']}-q{chapter_number:03d}",
        "question": questions[intent],
        "requestPayload": {
            "userId": chapter["userId"],
            "projectId": chapter["projectId"],
            "workId": chapter["workId"],
            "generationId": chapter["generationId"],
        },
        "evaluationCohort": {
            "intent": intent,
            "genre": chapter["genre"],
            "lengthBucket": chapter["lengthBucket"],
            "generation": str(chapter["generationId"]),
            "bookId": chapter["bookId"],
        },
        "applyProjectReleaseGate": True,
        "k": 5,
        "relevantSourceIds": [chapter["sourceId"]],
        "relevanceGrades": {chapter["sourceId"]: 3},
        "expectedChapterIds": [chapter["sourceId"]],
        "expectedForeshadowingIds": [foreshadowing_id],
        "expectedStructuredValues": chapter["structuredValues"],
        "expectedPathEdges": {edge_id: chapter["pathEdges"][edge_id]},
        "requireStaleRejection": True,
        "requireCrossUserIsolation": True,
        "retrievalThresholds": {
            "minRecallAt5": 0.95,
            "minRecallAt10": 0.95,
            "minChapterLocationAccuracy": 0.95,
            "minStructuredAccuracy": 0.95,
            "minForeshadowingCoverage": 0.90,
            "minMultiHopPathEvidence": 0.85,
            "minStaleRejectionRate": 1.0,
            "minCrossUserIsolationRate": 1.0,
        },
    }


def _marker(book_id: str, chapter_number: int) -> str:
    return f"clue-{book_id}-{chapter_number:03d}"


def _write_json(path: Path, payload: object) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
