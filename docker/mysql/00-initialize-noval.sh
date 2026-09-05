#!/usr/bin/env bash
# Keep this file non-executable so the official MySQL entrypoint sources it and
# exposes docker_process_sql. The SQL directory is mounted read-only elsewhere.

readonly NOVAL_MYSQL_SQL_DIR="/opt/noval/sql/mysql"
readonly NOVAL_MYSQL_INIT_SCRIPTS=(
    "phase2-schema.sql"
    "phase2-seed.sql"
    "phase3-schema.sql"
    "phase4-schema.sql"
    "phase4-seed.sql"
    "phase5-schema.sql"
    "phase5-seed.sql"
    "phase5-prompt-governance-repair.sql"
    "phase6-schema.sql"
    "phase7-knowledge-schema.sql"
    "phase8-history-pagination.sql"
    "phase8-knowledge-chat-memory-schema.sql"
    "phase9-knowledge-index-metadata-migration.sql"
    "phase10-history-search-index.sql"
    "phase11-rag-eval-observability.sql"
    "phase12-webnovel-agent-project-trace.sql"
    "phase13-agent-memory-mcp.sql"
    "phase14-ai-agent-production-upgrade.sql"
    "phase15-ai-chat-run-production.sql"
    "phase16-project-knowledge-rag.sql"
    "phase17-project-knowledge-ingest-upgrade.sql"
    "phase18-agent-harness-conversation-rag.sql"
    "phase19-durable-chat-run-execution.sql"
    "phase20-agent-tool-governance.sql"
    "phase21-agent-task7-production-hardening.sql"
    "phase22-agent-task7-review-hardening.sql"
    "phase23-skill-memory-lifecycle.sql"
    "phase24-project-ingest-generation.sql"
    "phase25-project-hybrid-retrieval-story-graph.sql"
    "phase26-project-retrieval-eval-observability.sql"
    "phase27-agent-skill-contract.sql"
    "phase28-mysql-resource-optimization.sql"
    "phase29-project-document-batch.sql"
    "phase30-long-form-memory-foundation.sql"
)

if ! declare -F docker_process_sql >/dev/null 2>&1; then
    printf >&2 'ERROR: official MySQL entrypoint function docker_process_sql is unavailable.\n'
    exit 1
fi

for sql_name in "${NOVAL_MYSQL_INIT_SCRIPTS[@]}"; do
    sql_path="${NOVAL_MYSQL_SQL_DIR}/${sql_name}"
    if [[ ! -r "${sql_path}" ]]; then
        printf >&2 'ERROR: required MySQL initialization script is missing or unreadable: %s\n' "${sql_path}"
        exit 1
    fi
done

for sql_name in "${NOVAL_MYSQL_INIT_SCRIPTS[@]}"; do
    sql_path="${NOVAL_MYSQL_SQL_DIR}/${sql_name}"
    printf 'Initializing Noval schema from %s\n' "${sql_name}"
    if ! docker_process_sql < "${sql_path}"; then
        printf >&2 'ERROR: MySQL initialization failed while executing %s\n' "${sql_name}"
        exit 1
    fi
done
