package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeProjectMemoryVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeProjectMemoryService {

    private static final Set<String> SUPPORTED_KEYS = Set.of(
        "premise",
        "genre",
        "protagonist",
        "powerSystem",
        "styleConstraints",
        "sellingPoints",
        "readerRisks",
        "revisionHistory"
    );

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeProjectService projectService;

    public KnowledgeProjectMemoryService(JdbcTemplate jdbcTemplate, KnowledgeProjectService projectService) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectService = projectService;
    }

    public KnowledgeProjectMemoryVO read(Long projectId, Long userId) {
        projectService.ensureOwned(projectId, userId);
        Map<String, String> memories = new LinkedHashMap<>();
        jdbcTemplate.query(
            "select memory_key, memory_value from ai_project_memory where project_id = ? and user_id = ? order by id asc",
            rs -> {
                memories.put(rs.getString("memory_key"), rs.getString("memory_value"));
            },
            projectId,
            userId
        );
        KnowledgeProjectMemoryVO vo = new KnowledgeProjectMemoryVO();
        vo.setProjectId(projectId);
        vo.setUserId(userId);
        vo.setMemories(memories);
        return vo;
    }

    public KnowledgeProjectMemoryVO upsert(Long projectId,
                                           Long userId,
                                           Map<String, String> memories,
                                           String sourceTraceId) {
        projectService.ensureOwned(projectId, userId);
        if (memories == null || memories.isEmpty()) {
            return read(projectId, userId);
        }
        for (Map.Entry<String, String> entry : memories.entrySet()) {
            String key = requireSupportedKey(entry.getKey());
            upsertOne(projectId, userId, key, entry.getValue(), trimToNull(sourceTraceId));
        }
        return read(projectId, userId);
    }

    private void upsertOne(Long projectId,
                           Long userId,
                           String key,
                           String value,
                           String sourceTraceId) {
        Long existingId = jdbcTemplate.query(
            "select id from ai_project_memory where project_id = ? and memory_key = ?",
            rs -> rs.next() ? rs.getLong("id") : null,
            projectId,
            key
        );
        if (existingId == null) {
            jdbcTemplate.update(
                "insert into ai_project_memory(project_id, user_id, memory_key, memory_value, source_trace_id) values(?, ?, ?, ?, ?)",
                projectId,
                userId,
                key,
                value,
                sourceTraceId
            );
            return;
        }
        jdbcTemplate.update(
            "update ai_project_memory set user_id = ?, memory_value = ?, source_trace_id = ?, updated_at = current_timestamp where id = ?",
            userId,
            value,
            sourceTraceId,
            existingId
        );
    }

    private String requireSupportedKey(String value) {
        String key = trimToNull(value);
        if (key == null || !SUPPORTED_KEYS.contains(key)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "unsupported project memory key");
        }
        return key;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
