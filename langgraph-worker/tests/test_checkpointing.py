from __future__ import annotations

import unittest
from typing import TypedDict

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, StateGraph

from app.services.checkpointing import (
    DurableMySqlSaver,
    MySqlCheckpointConfig,
    build_langgraph_checkpointer,
    checkpoint_store_name,
)


class CounterState(TypedDict):
    value: int


def _build_counter_graph(checkpointer: InMemorySaver):
    graph = StateGraph(CounterState)
    graph.add_node("increment", lambda state: {"value": state["value"] + 1})
    graph.set_entry_point("increment")
    graph.add_edge("increment", END)
    return graph.compile(checkpointer=checkpointer)


class CheckpointingTest(unittest.TestCase):
    def test_mysql_checkpointer_persists_graph_checkpoints_across_instances(self) -> None:
        connector = FakeMySqlConnector()
        config = {"configurable": {"thread_id": "trace-mysql-1"}}
        mysql_config = MySqlCheckpointConfig(
            host="mysql",
            port=3306,
            database="novel_analyzer",
            user="novel",
            password="pw",
        )

        first_saver = DurableMySqlSaver(mysql_config, namespace="test-agent", connector_factory=connector)
        first_graph = _build_counter_graph(first_saver)
        self.assertEqual({"value": 2}, first_graph.invoke({"value": 1}, config=config))

        second_saver = DurableMySqlSaver(mysql_config, namespace="test-agent", connector_factory=connector)
        persisted = second_saver.get_tuple(config)

        self.assertIsNotNone(persisted)
        assert persisted is not None
        self.assertEqual("trace-mysql-1", persisted.config["configurable"]["thread_id"])
        self.assertEqual(2, persisted.checkpoint["channel_values"]["value"])

    def test_factory_uses_mysql_when_checkpoint_backend_is_mysql(self) -> None:
        connector = FakeMySqlConnector()
        mysql_config = MySqlCheckpointConfig(
            host="mysql",
            port=3306,
            database="novel_analyzer",
            user="novel",
            password="pw",
        )

        checkpointer = build_langgraph_checkpointer(
            "novel-agent",
            checkpoint_backend="mysql",
            mysql_config=mysql_config,
            mysql_connector_factory=connector,
        )

        self.assertIsInstance(checkpointer, DurableMySqlSaver)
        self.assertEqual("mysql", checkpoint_store_name(checkpointer))

class FakeMySqlConnector:
    def __init__(self) -> None:
        self.rows: dict[str, bytes] = {}

    def __call__(self, config: MySqlCheckpointConfig):
        return FakeMySqlConnection(self.rows)


class FakeMySqlConnection:
    def __init__(self, rows: dict[str, bytes]) -> None:
        self.rows = rows
        self.committed = False
        self.closed = False

    def cursor(self):
        return FakeMySqlCursor(self.rows)

    def commit(self) -> None:
        self.committed = True

    def close(self) -> None:
        self.closed = True


class FakeMySqlCursor:
    def __init__(self, rows: dict[str, bytes]) -> None:
        self.rows = rows
        self._row = None

    def execute(self, sql: str, params=None) -> None:
        normalized = " ".join(sql.lower().split())
        if normalized.startswith("create table"):
            return
        if normalized.startswith("select payload"):
            namespace = params[0]
            payload = self.rows.get(namespace)
            self._row = (payload,) if payload is not None else None
            return
        if normalized.startswith("insert into"):
            namespace, payload = params
            self.rows[namespace] = bytes(payload)
            return
        raise AssertionError(f"unexpected SQL: {sql}")

    def fetchone(self):
        return self._row

    def close(self) -> None:
        return None


if __name__ == "__main__":
    unittest.main()
