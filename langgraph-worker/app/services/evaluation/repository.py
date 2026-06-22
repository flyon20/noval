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
    ) -> int:
        row_id = self._execute_insert(
            """
            INSERT INTO ai_eval_run(
                run_key, suite_name, runner_name, evaluator_name, model_name,
                settings_json, status, total_cases, passed_cases, failed_cases
            )
            VALUES (%s, %s, %s, %s, %s, %s, 'RUNNING', 0, 0, 0)
            """,
            (run_key, suite_name, runner_name, evaluator_name, model_name, self._dump_json(settings_json or {})),
        )
        return row_id

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
                metrics_json = %s,
                finished_at = CURRENT_TIMESTAMP
            WHERE id = %s
            """,
            (status, total_cases, passed_cases, failed_cases, self._dump_json(metrics_json), run_id),
        )

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
        )

    def _nullable_str(self, value: Any) -> str | None:
        if value in {None, ""}:
            return None
        return str(value)


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
