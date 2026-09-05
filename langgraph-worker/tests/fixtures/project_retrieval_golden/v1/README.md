# Project Retrieval Golden Corpus v1

- Corpus: 10 self-authored synthetic books, 150 chapters per book.
- Coverage: 1200 cases across 5 genres, 4 intents, 3 length buckets, and 10 active Generations.
- Isolation fixtures: every evaluated chapter has a higher-frequency retired decoy and cross-user decoy.
- Evaluation: scoped BM25 retrieval, retrieval metrics, bootstrap 95% confidence intervals, baseline comparison, and release Gate.
- Regenerate corpus: `python scripts/generate_project_retrieval_golden_v1.py`.
- Run Gate: `python scripts/run_project_retrieval_golden_v1.py`.
- Refresh baseline intentionally: `python scripts/run_project_retrieval_golden_v1.py --update-baseline`.

All chapter bodies are synthetic test text. Do not add private user content.
