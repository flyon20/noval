from __future__ import annotations

import json
from contextlib import closing
from dataclasses import dataclass
from typing import Any, Callable

from app.services.checkpointing import MySqlCheckpointConfig, MySqlConnectorFactory
from app.services.evaluation.golden import GoldenEvalCase, GoldenEvalCaseResult
from app.services.retrieval_eval import RetrievalEvalThresholds


@dataclass(frozen=True)
class MySqlGoldenEvalRepository:
    mysql_config: MySqlCheckpointConfig
    connector_factory: MySqlConnectorFactory | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "_connector_factory", self.connector_factory or _default_mysql_connector)

    def list_active_cases(self, suite_name: str, *, limit: int = 500) -> list[GoldenEvalCase]:
        rows = self._fetch_all(
            """
            SELECT id, suite_name, case_key, case_type, question, request_json,
                   expected_intent, expected_answer_mode, expected_sub_intents,
                   relevant_source_ids, required_source_types, forbidden_claims,
                   answer_rubric, retrieval_thresholds
            FROM ai_eval_golden_case
            WHERE suite_name = %s AND status = 'ACTIVE' AND deleted = 0
            ORDER BY id ASC
            LIMIT %s
            """,
            (suite_name, limit),
        )
        cases: list[GoldenEvalCase] = []
        for row in rows:
            request_payload = self._load_json(self._row_value(row, 5, "request_json"), default={})
            if not isinstance(request_payload, dict):
                request_payload = {}
            project_eval = request_payload.pop("_projectRetrievalEval", {})
            if not isinstance(project_eval, dict):
                project_eval = {}
            thresholds = self._load_thresholds(self._row_value(row, 13, "retrieval_thresholds"))
            cases.append(
                GoldenEvalCase(
                    case_id=str(self._row_value(row, 2, "case_key")),
                    question=str(self._row_value(row, 4, "question") or ""),
                    request_payload=request_payload,
                    expected_intent=self._nullable_str(self._row_value(row, 6, "expected_intent")),
                    expected_answer_mode=self._nullable_str(self._row_value(row, 7, "expected_answer_mode")),
                    expected_sub_intents=set(self._load_json(self._row_value(row, 8, "expected_sub_intents"), default=[])),
                    relevant_source_ids=set(self._load_json(self._row_value(row, 9, "relevant_source_ids"), default=[])),
                    forbidden_claims=list(self._load_json(self._row_value(row, 11, "forbidden_claims"), default=[])),
                    retrieval_thresholds=thresholds,
                    relevance_grades=self._float_mapping(project_eval.get("relevanceGrades")),
                    expected_chapter_ids=self._string_set(project_eval.get("expectedChapterIds")),
                    expected_foreshadowing_ids=self._string_set(project_eval.get("expectedForeshadowingIds")),
                    expected_structured_values=self._mapping(project_eval.get("expectedStructuredValues")),
                    expected_path_edges=self._edge_mapping(project_eval.get("expectedPathEdges")),
                    require_stale_rejection=self._bool_value(project_eval.get("requireStaleRejection")),
                    require_cross_user_isolation=self._bool_value(project_eval.get("requireCrossUserIsolation")),
                    evaluation_cohort={key: str(value) for key, value in self._mapping(project_eval.get("cohort")).items()},
                    apply_project_release_gate=self._bool_value(project_eval.get("applyProjectReleaseGate")),
                )
            )
        return cases

    def create_run(
        self,
        *,
        run_key: str,
        suite_name: str,
        runner_name: str,
        evaluator_name: str,
        model_name: str | None = None,
        settings_json: dict[str, Any] | None = None,
        total_cases: int = 0,
    ) -> int:
        row_id = self._execute_insert(
            """
            INSERT INTO ai_eval_run(
                run_key, suite_name, runner_name, evaluator_name, model_name,
                settings_json, status, total_cases, passed_cases, failed_cases
            )
            VALUES (%s, %s, %s, %s, %s, %s, 'RUNNING', %s, 0, 0)
            """,
            (
                run_key,
                suite_name,
                runner_name,
                evaluator_name,
                model_name,
                self._dump_json(settings_json or {}),
                max(0, int(total_cases or 0)),
            ),
        )
        if total_cases > 0:
            self.update_run_progress(run_id=row_id, current=0, total=total_cases, message="created")
        return row_id

    def update_run_progress(
        self,
        *,
        run_id: int,
        current: int,
        total: int,
        message: str,
    ) -> None:
        self._execute(
            """
            UPDATE ai_eval_run
            SET progress_current = %s,
                progress_total = %s,
                progress_message = %s,
                last_heartbeat_at = CURRENT_TIMESTAMP
            WHERE id = %s
            """,
            (current, total, message, run_id),
        )

    def record_case_result(
        self,
        *,
        run_id: int,
        result: GoldenEvalCaseResult,
        response_json: dict[str, Any],
        answer_text: str,
        duration_ms: int,
        case_id: int | None = None,
    ) -> None:
        self._execute(
            """
            INSERT INTO ai_eval_case_result(
                run_id, case_id, case_key, status, intent, answer_mode,
                retrieval_metrics, faithfulness_json, failures, trace_id,
                checkpoint_thread_id, response_json, answer_text, duration_ms
            )
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                run_id,
                case_id,
                result.case_id,
                result.status.upper(),
                result.intent,
                result.answer_mode,
                self._dump_json(result.retrieval_metrics),
                self._dump_json(result.faithfulness),
                self._dump_json(result.failures),
                result.trace.get("traceId"),
                result.trace.get("checkpointThreadId"),
                self._dump_json(response_json),
                answer_text,
                duration_ms,
            ),
        )

    def record_trace_event(
        self,
        *,
        run_id: int,
        case_key: str,
        node_name: str,
        event_type: str,
        sequence_no: int,
        duration_ms: int = 0,
        case_id: int | None = None,
        trace_id: str | None = None,
        checkpoint_thread_id: str | None = None,
        input_json: dict[str, Any] | None = None,
        output_json: dict[str, Any] | None = None,
        error_message: str | None = None,
    ) -> None:
        self._execute(
            """
            INSERT INTO ai_eval_trace_event(
                run_id, case_id, case_key, trace_id, checkpoint_thread_id,
                node_name, event_type, sequence_no, duration_ms,
                input_json, output_json, error_message
            )
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                run_id,
                case_id,
                case_key,
                trace_id,
                checkpoint_thread_id,
                node_name,
                event_type,
                sequence_no,
                duration_ms,
                self._dump_json(input_json or {}),
                self._dump_json(output_json or {}),
                error_message,
            ),
        )

    def finish_run(
        self,
        *,
        run_id: int,
        total_cases: int,
        passed_cases: int,
        failed_cases: int,
        metrics_json: dict[str, Any],
    ) -> None:
        status = "PASSED" if failed_cases <= 0 else "FAILED"
        self._execute(
            """
            UPDATE ai_eval_run
            SET status = %s,
                total_cases = %s,
                passed_cases = %s,
                failed_cases = %s,
                progress_current = %s,
                progress_total = %s,
                progress_message = %s,
                metrics_json = %s,
                finished_at = CURRENT_TIMESTAMP
            WHERE id = %s
            """,
            (
                status,
                total_cases,
                passed_cases,
                failed_cases,
                total_cases,
                total_cases,
                "completed",
                self._dump_json(metrics_json),
                run_id,
            ),
        )

    def fail_run(self, *, run_id: int, error_message: str) -> None:
        self._execute(
            """
            UPDATE ai_eval_run
            SET status = 'FAILED',
                metrics_json = %s,
                error_message = %s,
                progress_message = %s,
                finished_at = CURRENT_TIMESTAMP
            WHERE id = %s
            """,
            (self._dump_json({"error": error_message}), error_message, "failed", run_id),
        )

    def cancel_run(self, *, run_id: int, completed_cases: int, total_cases: int) -> None:
        self._execute(
            """
            UPDATE ai_eval_run
            SET status = 'CANCELLED',
                cancel_requested = TRUE,
                progress_current = %s,
                progress_total = %s,
                progress_message = %s,
                cancelled_at = COALESCE(cancelled_at, CURRENT_TIMESTAMP),
                finished_at = CURRENT_TIMESTAMP
            WHERE id = %s
            """,
            (completed_cases, total_cases, "cancelled", run_id),
        )

    def is_run_cancelled(self, run_id: int) -> bool:
        rows = self._fetch_all(
            """
            SELECT status, cancel_requested
            FROM ai_eval_run
            WHERE id = %s AND deleted = 0
            LIMIT 1
            """,
            (run_id,),
        )
        if not rows:
            return False
        status = str(self._row_value(rows[0], 0, "status") or "").upper()
        cancel_requested = self._row_value(rows[0], 1, "cancel_requested")
        return status in {"CANCELLING", "CANCELLED"} or self._bool_value(cancel_requested)

    def _fetch_all(self, sql: str, params: tuple[Any, ...]) -> list[tuple[Any, ...]]:
        with closing(self._connect()) as connection:
            with closing(connection.cursor()) as cursor:
                cursor.execute(sql, params)
                rows = cursor.fetchall()
        return list(rows or [])

    def _execute_insert(self, sql: str, params: tuple[Any, ...]) -> int:
        with closing(self._connect()) as connection:
            with closing(connection.cursor()) as cursor:
                cursor.execute(sql, params)
                row_id = int(getattr(cursor, "lastrowid", 0) or 0)
            connection.commit()
        return row_id

    def _execute(self, sql: str, params: tuple[Any, ...]) -> None:
        with closing(self._connect()) as connection:
            with closing(connection.cursor()) as cursor:
                cursor.execute(sql, params)
            connection.commit()

    def _connect(self) -> Any:
        return self._connector_factory(self.mysql_config)

    def _row_value(self, row: Any, index: int, key: str) -> Any:
        if isinstance(row, dict):
            return row.get(key)
        return row[index]

    def _dump_json(self, value: Any) -> str:
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    def _load_json(self, value: Any, *, default: Any) -> Any:
        if value in {None, ""}:
            return default
        if isinstance(value, (dict, list)):
            return value
        if isinstance(value, bytes):
            value = value.decode("utf-8")
        return json.loads(str(value))

    def _load_thresholds(self, value: Any) -> RetrievalEvalThresholds:
        data = self._load_json(value, default={})
        if not isinstance(data, dict):
            data = {}
        return RetrievalEvalThresholds(
            min_hit_rate_at_k=float(data.get("min_hit_rate_at_k") or data.get("minHitRateAtK") or 0.0),
            min_mrr_at_k=float(data.get("min_mrr_at_k") or data.get("minMrrAtK") or 0.0),
            min_context_precision_at_k=float(
                data.get("min_context_precision_at_k") or data.get("minContextPrecisionAtK") or 0.0
            ),
            min_context_recall_at_k=float(
                data.get("min_context_recall_at_k") or data.get("minContextRecallAtK") or 0.0
            ),
            min_recall_at_5=float(data.get("min_recall_at_5") or data.get("minRecallAt5") or 0.0),
            min_recall_at_10=float(data.get("min_recall_at_10") or data.get("minRecallAt10") or 0.0),
            min_precision_at_5=float(data.get("min_precision_at_5") or data.get("minPrecisionAt5") or 0.0),
            min_ndcg_at_10=float(data.get("min_ndcg_at_10") or data.get("minNdcgAt10") or 0.0),
            min_structured_accuracy=float(data.get("min_structured_accuracy") or data.get("minStructuredAccuracy") or 0.0),
            min_chapter_location_accuracy=float(
                data.get("min_chapter_location_accuracy") or data.get("minChapterLocationAccuracy") or 0.0
            ),
            min_foreshadowing_coverage=float(
                data.get("min_foreshadowing_coverage") or data.get("minForeshadowingCoverage") or 0.0
            ),
            min_multi_hop_path_evidence=float(
                data.get("min_multi_hop_path_evidence") or data.get("minMultiHopPathEvidence") or 0.0
            ),
            min_stale_rejection_rate=float(
                data.get("min_stale_rejection_rate") or data.get("minStaleRejectionRate") or 0.0
            ),
            min_cross_user_isolation_rate=float(
                data.get("min_cross_user_isolation_rate") or data.get("minCrossUserIsolationRate") or 0.0
            ),
        )

    def _string_set(self, value: Any) -> set[str]:
        if not isinstance(value, list):
            return set()
        return {str(item) for item in value if str(item).strip()}

    def _mapping(self, value: Any) -> dict[str, Any]:
        return {str(key): item for key, item in value.items()} if isinstance(value, dict) else {}

    def _float_mapping(self, value: Any) -> dict[str, float]:
        result: dict[str, float] = {}
        for key, item in self._mapping(value).items():
            try:
                result[key] = float(item)
            except (TypeError, ValueError):
                continue
        return result

    def _edge_mapping(self, value: Any) -> dict[str, set[str]]:
        return {key: self._string_set(item) for key, item in self._mapping(value).items()}

    def _nullable_str(self, value: Any) -> str | None:
        if value in {None, ""}:
            return None
        return str(value)

    def _bool_value(self, value: Any) -> bool:
        if isinstance(value, bool):
            return value
        if isinstance(value, (int, float)):
            return value != 0
        return str(value).strip().lower() in {"1", "true", "yes", "on"}


def _default_mysql_connector(config: MySqlCheckpointConfig) -> Any:
    try:
        import pymysql
    except ModuleNotFoundError as exc:
        raise RuntimeError("PyMySQL is required for MySQL golden eval repository") from exc

    return pymysql.connect(
        host=config.host,
        port=config.port,
        database=config.database,
        user=config.user,
        password=config.password,
        charset=config.charset,
        connect_timeout=config.connect_timeout,
        autocommit=False,
    )
