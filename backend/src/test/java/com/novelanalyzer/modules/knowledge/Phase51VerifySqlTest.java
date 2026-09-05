package com.novelanalyzer.modules.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Phase51VerifySqlTest {

    @Test
    void shouldShipPhase24To30VerifyScriptsWithZeroMismatchContracts() throws Exception {
        String p24 = Files.readString(resolve("phase24-project-ingest-generation-verify.sql"), StandardCharsets.UTF_8);
        String p25 = Files.readString(resolve("phase25-project-hybrid-retrieval-story-graph-verify.sql"), StandardCharsets.UTF_8);
        String p26 = Files.readString(resolve("phase26-project-retrieval-eval-observability-verify.sql"), StandardCharsets.UTF_8);
        String p27 = Files.readString(resolve("phase27-agent-skill-contract-verify.sql"), StandardCharsets.UTF_8);
        String p28 = Files.readString(resolve("phase28-mysql-resource-optimization-verify.sql"), StandardCharsets.UTF_8);
        String p29 = Files.readString(resolve("phase29-project-document-batch-verify.sql"), StandardCharsets.UTF_8);
        String p30 = Files.readString(resolve("phase30-long-form-memory-foundation-verify.sql"), StandardCharsets.UTF_8);

        assertThat(p24).contains("phase24_duplicate_active_generation_per_chapter", "phase24_outbox_orphans");
        assertThat(p25).contains("phase25_story_edge_orphan_from_node", "phase25_search_document_scope_mismatch");
        assertThat(p26)
            .contains(
                "phase26_feedback_without_project_scope",
                "phase26_missing_core_tables",
                "phase26_eval_baseline_missing_identity",
                "phase26_resource_diagnostic_invalid_json"
            )
            .doesNotContain("metric_name", "diagnostic_key");
        assertThat(p27).contains(
            "phase27_missing_skill_contract_columns",
            "phase27_invalid_candidate_capability_json",
            "phase27_active_runtime_skill_missing_identity"
        );
        assertThat(p28).contains(
            "phase28_missing_crawl_book_lookup_index",
            "phase28_missing_crawl_rank_latest_index",
            "phase28_missing_knowledge_chunk_source_status_index",
            "platform,platform_book_id,deleted",
            "platform,category,deleted,crawl_time,rank_no,id",
            "source_type,source_ref_id,deleted,vector_status,chunk_strategy_version,embedding_model,embedding_dimension"
        );
        assertThat(p29).contains(
            "phase29_missing_document_batch_tables",
            "phase29_document_file_scope_mismatch",
            "phase29_outbox_orphans",
            "phase29_missing_vector_document_columns"
        );
        assertThat(p30).contains(
            "phase30_missing_long_form_memory_tables",
            "phase30_fact_evidence_scope_mismatch",
            "phase30_fact_evidence_orphans",
            "phase30_invalid_fact_validity_range",
            "phase30_invalid_summary_range",
            "phase30_summary_evidence_scope_mismatch",
            "phase30_summary_evidence_orphans"
        );
        assertThat(p24 + p25 + p26 + p27 + p28 + p29 + p30).doesNotContain("DROP TABLE");
    }

    @Test
    void phase27MigrationAvoidsUnsupportedMySqlAlterSyntax() throws Exception {
        String migration = Files.readString(
            resolve("phase27-agent-skill-contract.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(migration)
            .doesNotContain("ADD COLUMN IF NOT EXISTS")
            .contains(
                "noval_phase27_add_column_if_missing",
                "information_schema.columns",
                "PREPARE stmt FROM @ddl"
            );
    }

    @Test
    void phase28MigrationAddsMeasuredIndexesIdempotentlyAndOnline() throws Exception {
        String migration = Files.readString(
            resolve("phase28-mysql-resource-optimization.sql"),
            StandardCharsets.UTF_8
        );

        assertThat(migration)
            .contains(
                "information_schema.statistics",
                "idx_crawl_book_platform_book_deleted",
                "idx_crawl_rank_latest_lookup",
                "idx_knowledge_chunk_source_status",
                "PREPARE phase28_stmt FROM @phase28_crawl_book_lookup_index_ddl",
                "PREPARE phase28_stmt FROM @phase28_crawl_rank_latest_index_ddl",
                "PREPARE phase28_stmt FROM @phase28_knowledge_chunk_source_status_index_ddl",
                "ALGORITHM=INPLACE, LOCK=NONE"
            )
            .doesNotContain(
                "idx_crawl_rank_snapshot_page",
                "ADD INDEX IF NOT EXISTS",
                "DROP INDEX",
                "DROP TABLE"
            );
    }

    private Path resolve(String name) throws Exception {
        Path[] candidates = new Path[] {
            Path.of("sql/mysql").resolve(name),
            Path.of("src/main/resources/sql/mysql").resolve(name),
            Path.of("backend/sql/mysql").resolve(name),
            Path.of("..", "sql/mysql").resolve(name)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new java.nio.file.NoSuchFileException(name);
    }
}
