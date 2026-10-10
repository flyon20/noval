from __future__ import annotations

import json
import unittest

from app.services.checkpointing import MySqlCheckpointConfig
from app.services.evaluation import GoldenEvalCaseResult
from app.services.evaluation.repository import MySqlGoldenEvalRepository


class GoldenEvalRepositoryTest(unittest.TestCase):
    def test_reads_active_golden_cases_from_mysql(self) -> None:
        connector = FakeEvalMySqlConnector()
        connector.tables["ai_eval_golden_case"].append({
            "id": 1,
            "suite_name": "rag-smoke",
            "case_key": "mixed-001",
            "case_type": "mixed_creation",
            "question": "根据当前男频新书榜都市脑洞第一的书，细纲怎么设计？",
            "request_json": json.dumps({
                "mode": "research",
                "limits": {"evidenceLimit": 8},
                "_projectRetrievalEval": {
                    "relevanceGrades": {"chapter:12": 3},
                    "expectedChapterIds": ["chapter:12"],
                    "expectedForeshadowingIds": ["foreshadowing:moon"],
                    "expectedStructuredValues": {"character:hero:status": "injured"},
                    "expectedPathEdges": {"edge:101": ["chapter:12"]},
                    "requireStaleRejection": True,
                    "requireCrossUserIsolation": True,
                    "cohort": {"genre": "urban", "generation": "77"},
                    "applyProjectReleaseGate": True,
                },
            }, ensure_ascii=False),
            "expected_intent": "mixed_creation_research",
            "expected_answer_mode": "mixed_creation",
            "expected_sub_intents": json.dumps(["market_scan", "chapter_outline"], ensure_ascii=False),
            "relevant_source_ids": json.dumps(["rank:101", "chunk:31"], ensure_ascii=False),
            "forbidden_claims": json.dumps(["世界首富"], ensure_ascii=False),
            "retrieval_thresholds": json.dumps({
                "min_hit_rate_at_k": 1.0,
                "min_context_recall_at_k": 0.8,
            }),
            "status": "ACTIVE",
            "deleted": 0,
        })
        repository = MySqlGoldenEvalRepository(
            mysql_config=_mysql_config(),
            connector_factory=connector,
        )

        cases = repository.list_active_cases("rag-smoke")

        self.assertEqual(1, len(cases))
        case = cases[0]
        self.assertEqual("mixed-001", case.case_id)
        self.assertEqual("mixed_creation_research", case.expected_intent)
        self.assertEqual({"market_scan", "chapter_outline"}, case.expected_sub_intents)
        self.assertEqual({"rank:101", "chunk:31"}, case.relevant_source_ids)
        self.assertEqual(["世界首富"], case.forbidden_claims)
        self.assertEqual(1.0, case.retrieval_thresholds.min_hit_rate_at_k)
        self.assertEqual(0.8, case.retrieval_thresholds.min_context_recall_at_k)
        self.assertEqual("research", case.request_payload["mode"])
        self.assertNotIn("_projectRetrievalEval", case.request_payload)
        self.assertEqual({"chapter:12": 3.0}, case.relevance_grades)
        self.assertEqual({"chapter:12"}, case.expected_chapter_ids)
        self.assertEqual({"foreshadowing:moon"}, case.expected_foreshadowing_ids)
        self.assertEqual({"character:hero:status": "injured"}, case.expected_structured_values)
        self.assertEqual({"edge:101": {"chapter:12"}}, case.expected_path_edges)
        self.assertTrue(case.require_stale_rejection)
        self.assertTrue(case.require_cross_user_isolation)
        self.assertEqual({"genre": "urban", "generation": "77"}, case.evaluation_cohort)
        self.assertTrue(case.apply_project_release_gate)

    def test_writes_eval_run_and_case_result_to_mysql(self) -> None:
        connector = FakeEvalMySqlConnector()
        repository = MySqlGoldenEvalRepository(
            mysql_config=_mysql_config(),
            connector_factory=connector,
        )

        run_id = repository.create_run(
            run_key="run-001",
            suite_name="rag-smoke",
            runner_name="local-runner",
            evaluator_name="rule-based",
            model_name="deepseek-chat",
            settings_json={"k": 5},
        )
        repository.record_case_result(
            run_id=run_id,
            result=GoldenEvalCaseResult(
                case_id="mixed-001",
                status="passed",
                intent="mixed_creation_research",
                answer_mode="mixed_creation",
                retrieval_metrics={"hit_rate_at_k": 1.0},
                faithfulness={"passed": True},
                failures=[],
                trace={"traceId": "trace-1", "checkpointThreadId": "thread-1"},
            ),
            response_json={"status": "answered"},
            answer_text="answer",
            duration_ms=123,
        )
        repository.finish_run(
            run_id=run_id,
            total_cases=1,
            passed_cases=1,
            failed_cases=0,
            metrics_json={"hit_rate_at_k": 1.0},
        )

        self.assertEqual(1, len(connector.tables["ai_eval_run"]))
        self.assertEqual("PASSED", connector.tables["ai_eval_run"][0]["status"])
        self.assertEqual(1, connector.tables["ai_eval_run"][0]["total_cases"])
        self.assertEqual(1, len(connector.tables["ai_eval_case_result"]))
        case_row = connector.tables["ai_eval_case_result"][0]
        self.assertEqual(run_id, case_row["run_id"])
        self.assertEqual("mixed-001", case_row["case_key"])
        self.assertEqual("PASSED", case_row["status"])
        self.assertEqual("trace-1", case_row["trace_id"])
        self.assertEqual("thread-1", case_row["checkpoint_thread_id"])

    def test_marks_eval_run_failed_when_background_execution_errors(self) -> None:
        connector = FakeEvalMySqlConnector()
        repository = MySqlGoldenEvalRepository(
            mysql_config=_mysql_config(),
            connector_factory=connector,
        )
        run_id = repository.create_run(
            run_key="run-error",
            suite_name="rag-smoke",
            runner_name="admin-trigger",
            evaluator_name="rule-based",
            model_name=None,
            settings_json={},
        )

        repository.fail_run(run_id=run_id, error_message="provider timeout")

        self.assertEqual("FAILED", connector.tables["ai_eval_run"][0]["status"])
        self.assertIn("provider timeout", connector.tables["ai_eval_run"][0]["metrics_json"])

    def test_create_run_persists_initial_total_cases(self) -> None:
        connector = FakeEvalMySqlConnector()
        repository = MySqlGoldenEvalRepository(
            mysql_config=_mysql_config(),
            connector_factory=connector,
        )

        repository.create_run(
            run_key="run-total",
            suite_name="rag-smoke",
            runner_name="admin-trigger",
            evaluator_name="rule-based",
            total_cases=3,
        )

        self.assertEqual(3, connector.tables["ai_eval_run"][0]["total_cases"])


def _mysql_config() -> MySqlCheckpointConfig:
    return MySqlCheckpointConfig(
        host="mysql",
        port=3306,
        database="novel_analyzer",
        user="novel",
        password="pw",
    )


class FakeEvalMySqlConnector:
    def __init__(self) -> None:
        self.tables = {
            "ai_eval_golden_case": [],
            "ai_eval_run": [],
            "ai_eval_case_result": [],
        }

    def __call__(self, config: MySqlCheckpointConfig):
        return FakeEvalMySqlConnection(self.tables)


class FakeEvalMySqlConnection:
    def __init__(self, tables: dict[str, list[dict]]) -> None:
        self.tables = tables
        self.committed = False

    def cursor(self):
        return FakeEvalMySqlCursor(self.tables)

    def commit(self) -> None:
        self.committed = True

    def close(self) -> None:
        return None


class FakeEvalMySqlCursor:
    def __init__(self, tables: dict[str, list[dict]]) -> None:
        self.tables = tables
        self._rows: list[dict] = []
        self.lastrowid = 0

    def execute(self, sql: str, params=None) -> None:
        normalized = " ".join(sql.lower().split())
        if normalized.startswith("select"):
            suite_name = params[0]
            limit = params[1]
            self._rows = [
                row for row in self.tables["ai_eval_golden_case"]
                if row["suite_name"] == suite_name and row["status"] == "ACTIVE" and row["deleted"] == 0
            ][:limit]
            return
        if normalized.startswith("insert into ai_eval_run"):
            row = {
                "id": len(self.tables["ai_eval_run"]) + 1,
                "run_key": params[0],
                "suite_name": params[1],
                "runner_name": params[2],
                "evaluator_name": params[3],
                "model_name": params[4],
                "settings_json": params[5],
                "status": "RUNNING",
                "total_cases": params[6],
                "passed_cases": 0,
                "failed_cases": 0,
            }
            self.tables["ai_eval_run"].append(row)
            self.lastrowid = row["id"]
            return
        if normalized.startswith("insert into ai_eval_case_result"):
            columns = [
                "run_id",
                "case_id",
                "case_key",
                "status",
                "intent",
                "answer_mode",
                "retrieval_metrics",
                "faithfulness_json",
                "failures",
                "trace_id",
                "checkpoint_thread_id",
                "response_json",
                "answer_text",
                "duration_ms",
            ]
            self.tables["ai_eval_case_result"].append(dict(zip(columns, params)))
            return
        if normalized.startswith("update ai_eval_run"):
            run_id = params[-1]
            for row in self.tables["ai_eval_run"]:
                if row["id"] == run_id:
                    if "status = 'failed'" in normalized:
                        row.update({
                            "status": "FAILED",
                            "metrics_json": params[0],
                            "error_message": params[1],
                            "progress_message": params[2],
                        })
                    elif "progress_current" in normalized and "status =" not in normalized:
                        row.update({
                            "progress_current": params[0],
                            "progress_total": params[1],
                            "progress_message": params[2],
                        })
                    else:
                        row.update({
                            "status": params[0],
                            "total_cases": params[1],
                            "passed_cases": params[2],
                            "failed_cases": params[3],
                            "progress_current": params[4],
                            "progress_total": params[5],
                            "progress_message": params[6],
                            "metrics_json": params[7],
                        })
            return
        raise AssertionError(f"unexpected SQL: {sql}")

    def fetchall(self):
        return list(self._rows)

    def close(self) -> None:
        return None


if __name__ == "__main__":
    unittest.main()
