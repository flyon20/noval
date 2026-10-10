from __future__ import annotations

import pickle
import threading
from collections import defaultdict
from contextlib import closing
from dataclasses import dataclass
from typing import Any, Callable

from langgraph.checkpoint.memory import InMemorySaver

from app.config import settings


@dataclass(frozen=True)
class MySqlCheckpointConfig:
    host: str
    port: int
    database: str
    user: str
    password: str
    charset: str = "utf8mb4"
    connect_timeout: int = 5

    @classmethod
    def from_settings(cls) -> MySqlCheckpointConfig:
        return cls(
            host=settings.mysql_host,
            port=settings.mysql_port,
            database=settings.mysql_database,
            user=settings.mysql_user,
            password=settings.mysql_password,
        )


MySqlConnectorFactory = Callable[[MySqlCheckpointConfig], Any]


class _PersistentStateSaver(InMemorySaver):
    def __init__(self, *, namespace: str) -> None:
        super().__init__()
        self.namespace = namespace
        self._lock = threading.RLock()
        self._ensure_schema()
        self._load_state()

    def put(self, config: Any, checkpoint: Any, metadata: Any, new_versions: Any) -> Any:
        with self._lock:
            updated = super().put(config, checkpoint, metadata, new_versions)
            self._persist_config_state(updated)
            return updated

    def put_writes(
        self,
        config: Any,
        writes: Any,
        task_id: str,
        task_path: str = "",
    ) -> None:
        with self._lock:
            super().put_writes(config, writes, task_id, task_path)
            self._persist_config_state(config)

    def delete_thread(self, thread_id: str) -> None:
        with self._lock:
            super().delete_thread(thread_id)
            self._delete_thread_state(thread_id)

    def _persist_config_state(self, config: Any) -> None:
        configurable = config.get("configurable") if isinstance(config, dict) else None
        if not isinstance(configurable, dict):
            raise ValueError("checkpoint config is missing configurable values")
        thread_id = str(configurable.get("thread_id") or "").strip()
        if not thread_id:
            raise ValueError("checkpoint config is missing thread_id")
        checkpoint_ns = str(configurable.get("checkpoint_ns") or "")
        self._persist_thread_state(thread_id, checkpoint_ns)

    def _state_payload(self) -> bytes:
        return pickle.dumps(
            {
                "storage": self._plain_dict(self.storage),
                "writes": dict(self.writes),
                "blobs": dict(self.blobs),
            },
            protocol=pickle.HIGHEST_PROTOCOL,
        )

    def _restore_state(self, payload: bytes) -> None:
        decoded = pickle.loads(payload)
        self.storage = self._restore_storage(decoded.get("storage", {}))
        self.writes = defaultdict(dict, decoded.get("writes", {}))
        self.blobs = dict(decoded.get("blobs", {}))

    def _row_payload(self, thread_id: str, checkpoint_ns: str) -> bytes:
        writes = {
            key: value
            for key, value in self.writes.items()
            if len(key) >= 2 and key[0] == thread_id and key[1] == checkpoint_ns
        }
        blobs = {
            key: value
            for key, value in self.blobs.items()
            if len(key) >= 2 and key[0] == thread_id and key[1] == checkpoint_ns
        }
        return pickle.dumps(
            {
                "thread_id": thread_id,
                "checkpoint_ns": checkpoint_ns,
                "checkpoints": self._plain_dict(self.storage[thread_id][checkpoint_ns]),
                "writes": writes,
                "blobs": blobs,
            },
            protocol=pickle.HIGHEST_PROTOCOL,
        )

    def _restore_row_state(self, thread_id: str, checkpoint_ns: str, payload: bytes) -> None:
        decoded = pickle.loads(payload)
        restored_thread_id = str(decoded.get("thread_id") or thread_id)
        restored_checkpoint_ns = str(decoded.get("checkpoint_ns") or checkpoint_ns)
        self.storage[restored_thread_id][restored_checkpoint_ns] = dict(decoded.get("checkpoints") or {})
        for key, value in dict(decoded.get("writes") or {}).items():
            self.writes[key] = value
        self.blobs.update(dict(decoded.get("blobs") or {}))

    def _restore_storage(self, payload: dict[str, Any]) -> defaultdict:
        storage = defaultdict(lambda: defaultdict(dict))
        for thread_id, namespace_payload in payload.items():
            checkpoint_namespaces = defaultdict(dict)
            for checkpoint_ns, checkpoints in dict(namespace_payload).items():
                checkpoint_namespaces[checkpoint_ns] = dict(checkpoints)
            storage[thread_id] = checkpoint_namespaces
        return storage

    def _plain_dict(self, value: Any) -> Any:
        if isinstance(value, defaultdict):
            return {key: self._plain_dict(item) for key, item in value.items()}
        if isinstance(value, dict):
            return {key: self._plain_dict(item) for key, item in value.items()}
        return value

    def _ensure_schema(self) -> None:
        raise NotImplementedError

    def _load_state(self) -> None:
        raise NotImplementedError

    def _persist_thread_state(self, thread_id: str, checkpoint_ns: str) -> None:
        raise NotImplementedError

    def _delete_thread_state(self, thread_id: str) -> None:
        return None


class DurableMySqlSaver(_PersistentStateSaver):
    """MySQL-backed LangGraph saver for the production worker stack."""

    def __init__(
        self,
        mysql_config: MySqlCheckpointConfig,
        *,
        namespace: str,
        connector_factory: MySqlConnectorFactory | None = None,
    ) -> None:
        self.mysql_config = mysql_config
        self._connector_factory = connector_factory or _default_mysql_connector
        super().__init__(namespace=namespace)

    def _connect(self) -> Any:
        return self._connector_factory(self.mysql_config)

    def _ensure_schema(self) -> None:
        with closing(self._connect()) as connection:
            with closing(connection.cursor()) as cursor:
                cursor.execute(
                    """
                    CREATE TABLE IF NOT EXISTS langgraph_checkpoint_thread_state (
                        namespace VARCHAR(191) NOT NULL,
                        thread_id VARCHAR(191) NOT NULL,
                        checkpoint_ns VARCHAR(191) NOT NULL,
                        payload LONGBLOB NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                            ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY(namespace, thread_id, checkpoint_ns)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """
                )
            connection.commit()

    def _load_state(self) -> None:
        with closing(self._connect()) as connection:
            with closing(connection.cursor()) as cursor:
                cursor.execute(
                    """
                    SELECT thread_id, checkpoint_ns, payload
                    FROM langgraph_checkpoint_thread_state
                    WHERE namespace = %s
                    """,
                    (self.namespace,),
                )
                rows = cursor.fetchall()
        for thread_id, checkpoint_ns, payload in rows:
            self._restore_row_state(str(thread_id), str(checkpoint_ns or ""), payload)

    def _persist_thread_state(self, thread_id: str, checkpoint_ns: str) -> None:
        with closing(self._connect()) as connection:
            with closing(connection.cursor()) as cursor:
                cursor.execute(
                    """
                    INSERT INTO langgraph_checkpoint_thread_state(
                        namespace, thread_id, checkpoint_ns, payload, updated_at
                    )
                    VALUES (%s, %s, %s, %s, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE
                        payload = VALUES(payload),
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    (
                        self.namespace,
                        thread_id,
                        checkpoint_ns,
                        self._row_payload(thread_id, checkpoint_ns),
                    ),
                )
            connection.commit()

    def _delete_thread_state(self, thread_id: str) -> None:
        with closing(self._connect()) as connection:
            with closing(connection.cursor()) as cursor:
                cursor.execute(
                    """
                    DELETE FROM langgraph_checkpoint_thread_state
                    WHERE namespace = %s AND thread_id = %s
                    """,
                    (self.namespace, thread_id),
                )
            connection.commit()


def build_langgraph_checkpointer(
    namespace: str,
    *,
    checkpoint_backend: str | None = None,
    mysql_config: MySqlCheckpointConfig | None = None,
    mysql_connector_factory: MySqlConnectorFactory | None = None,
) -> InMemorySaver:
    backend = (checkpoint_backend if checkpoint_backend is not None else settings.langgraph_checkpoint_backend).strip().lower()
    if backend == "mysql":
        return DurableMySqlSaver(
            mysql_config or MySqlCheckpointConfig.from_settings(),
            namespace=namespace,
            connector_factory=mysql_connector_factory,
        )
    if backend in {"", "memory"}:
        return InMemorySaver()
    raise ValueError(f"Unsupported LangGraph checkpoint backend: {backend}")


def checkpoint_store_name(checkpointer: Any) -> str:
    if isinstance(checkpointer, DurableMySqlSaver):
        return "mysql"
    return "memory"


def _default_mysql_connector(config: MySqlCheckpointConfig) -> Any:
    try:
        import pymysql
    except ModuleNotFoundError as exc:
        raise RuntimeError("PyMySQL is required for MySQL LangGraph checkpointing") from exc

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
