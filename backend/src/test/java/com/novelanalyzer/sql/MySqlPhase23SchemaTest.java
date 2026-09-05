package com.novelanalyzer.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlPhase23SchemaTest {

    private static final Path SCRIPT = Path.of("..", "sql", "mysql", "phase23-skill-memory-lifecycle.sql");

    @Test
    void phase23AddsVersionedSkillLifecycleAndTrustedMemoryProvenance() throws Exception {
        String sql = Files.readString(SCRIPT, StandardCharsets.UTF_8);

        assertThat(sql)
            .contains("ai_skill_lifecycle_audit")
            .contains("content_hash")
            .contains("input_schema_json")
            .contains("output_schema_json")
            .contains("allowed_tools_json")
            .contains("SHA2(CONVERT(content USING utf8mb4), 256)")
            .contains("WHEN 'PENDING' THEN 'DRAFT'")
            .contains("WHEN 'PUBLISHED' THEN 'ACTIVE'")
            .contains("WHEN 'DISABLED' THEN 'REVOKED'")
            .contains("ai_memory_lifecycle_audit")
            .contains("provenance_json")
            .contains("evidence_json")
            .contains("source_evidence_ids_json")
            .contains("supersedes_id")
            .contains("candidate_key")
            .contains("uk_ai_memory_candidate_idempotency")
            .contains("WHEN 'confirmed' THEN 'CONFIRMED'")
            .contains("WHEN 'expired' THEN 'STALE'")
            .contains("SET candidate.lifecycle_status = 'APPROVED', candidate.status = 'APPROVED'")
            .contains("WHERE candidate.lifecycle_status = 'ACTIVE' AND runtime_skill.id IS NULL");
        assertThat(sql)
            .contains("Self-consistent re-hashing of drifted runtime content")
            .contains("runtime_skill.content_hash <=> candidate.content_hash")
            .contains("runtime_skill.eval_result_json <=> candidate.eval_result_json");
    }
}
