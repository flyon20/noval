package com.novelanalyzer.modules.knowledge.service;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeProjectRequest;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeProjectVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeProjectService {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeProjectService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public KnowledgeProjectVO create(KnowledgeProjectRequest request) {
        AuthUser user = requireUser();
        String name = requireName(request == null ? null : request.getName());
        String description = trimToNull(request == null ? null : request.getDescription());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into ai_project(user_id, name, description, status) values(?, ?, ?, 'ACTIVE')",
                new String[]{"project_id"}
            );
            statement.setLong(1, user.getUserId());
            statement.setString(2, name);
            statement.setString(3, description);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "project id missing");
        }
        return findOwned(key.longValue(), user.getUserId());
    }

    public List<KnowledgeProjectVO> listMine() {
        AuthUser user = requireUser();
        return jdbcTemplate.query(
            "select project_id, user_id, name, description, status, created_at, updated_at " +
                "from ai_project where user_id = ? and status <> 'ARCHIVED' order by updated_at desc, project_id desc",
            mapper(),
            user.getUserId()
        );
    }

    public KnowledgeProjectVO rename(Long projectId, KnowledgeProjectRequest request) {
        AuthUser user = requireUser();
        KnowledgeProjectVO existing = findActiveOwned(projectId, user.getUserId());
        String name = requireName(request == null ? null : request.getName());
        String description = trimToNull(request == null ? null : request.getDescription());
        jdbcTemplate.update(
            "update ai_project set name = ?, description = ?, updated_at = current_timestamp where project_id = ? and user_id = ?",
            name,
            description == null ? existing.getDescription() : description,
            projectId,
            user.getUserId()
        );
        return findOwned(projectId, user.getUserId());
    }

    public void archive(Long projectId) {
        AuthUser user = requireUser();
        findActiveOwned(projectId, user.getUserId());
        jdbcTemplate.update(
            "update ai_project set status = 'ARCHIVED', updated_at = current_timestamp where project_id = ? and user_id = ?",
            projectId,
            user.getUserId()
        );
    }

    public void ensureOwned(Long projectId, Long userId) {
        if (projectId == null) {
            return;
        }
        findActiveOwned(projectId, userId);
    }

    public void ensureWorkOwned(Long projectId, Long workId, Long userId) {
        if (projectId == null || workId == null || userId == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "work not found");
        }
        findActiveOwned(projectId, userId);
        Integer count = jdbcTemplate.queryForObject(
            "select count(1) from ai_project_work where project_id = ? and work_id = ? and user_id = ? and status <> 'ARCHIVED'",
            Integer.class,
            projectId,
            workId,
            userId
        );
        if (count == null || count == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "work not found");
        }
    }

    public List<ReferenceWorkScope> resolveReferenceWorks(Long userId,
                                                          List<Long> referenceWorkIds,
                                                          Long activeProjectId,
                                                          Long activeWorkId) {
        if (referenceWorkIds == null || referenceWorkIds.isEmpty()) {
            return List.of();
        }
        if (userId == null || activeProjectId == null || activeWorkId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "active project and work are required for references");
        }
        ensureWorkOwned(activeProjectId, activeWorkId, userId);
        LinkedHashSet<Long> orderedIds = new LinkedHashSet<>();
        for (Long workId : referenceWorkIds) {
            if (workId == null || workId <= 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "reference work id is invalid");
            }
            if (!workId.equals(activeWorkId)) {
                orderedIds.add(workId);
            }
        }
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        if (orderedIds.size() > 8) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "too many reference works");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(orderedIds.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(userId);
        params.addAll(orderedIds);
        List<ReferenceWorkScope> rows = jdbcTemplate.query(
            "select w.project_id, w.work_id, w.title from ai_project_work w " +
                "join ai_project p on p.project_id = w.project_id and p.user_id = w.user_id " +
                "where w.user_id = ? and w.status <> 'ARCHIVED' and p.status <> 'ARCHIVED' " +
                "and w.work_id in (" + placeholders + ")",
            (rs, rowNum) -> new ReferenceWorkScope(
                rs.getLong("project_id"),
                rs.getLong("work_id"),
                rs.getString("title")
            ),
            params.toArray()
        );
        Map<Long, ReferenceWorkScope> byWorkId = new LinkedHashMap<>();
        for (ReferenceWorkScope row : rows) {
            byWorkId.put(row.workId(), row);
        }
        if (byWorkId.size() != orderedIds.size()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "reference work not found");
        }
        return orderedIds.stream().map(byWorkId::get).toList();
    }

    public void bindConversation(Long projectId, Long userId, String conversationId) {
        if (projectId == null || userId == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        findActiveOwned(projectId, userId);
        Integer conflictCount = jdbcTemplate.queryForObject(
            "select count(1) from ai_project_conversation where conversation_id = ? and user_id = ? and project_id <> ?",
            Integer.class,
            conversationId,
            userId,
            projectId
        );
        if (conflictCount != null && conflictCount > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "conversation belongs to another project");
        }
        Integer existingCount = jdbcTemplate.queryForObject(
            "select count(1) from ai_project_conversation where project_id = ? and conversation_id = ?",
            Integer.class,
            projectId,
            conversationId
        );
        if (existingCount != null && existingCount > 0) {
            return;
        }
        jdbcTemplate.update(
            "insert into ai_project_conversation(project_id, user_id, conversation_id) values(?, ?, ?)",
            projectId,
            userId,
            conversationId
        );
    }

    private KnowledgeProjectVO findOwned(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project not found");
        }
        List<KnowledgeProjectVO> projects = jdbcTemplate.query(
            "select project_id, user_id, name, description, status, created_at, updated_at " +
                "from ai_project where project_id = ? and user_id = ?",
            mapper(),
            projectId,
            userId
        );
        if (projects.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project not found");
        }
        return projects.get(0);
    }

    private KnowledgeProjectVO findActiveOwned(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project not found");
        }
        List<KnowledgeProjectVO> projects = jdbcTemplate.query(
            "select project_id, user_id, name, description, status, created_at, updated_at " +
                "from ai_project where project_id = ? and user_id = ? and status <> 'ARCHIVED'",
            mapper(),
            projectId,
            userId
        );
        if (projects.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project not found");
        }
        return projects.get(0);
    }

    private RowMapper<KnowledgeProjectVO> mapper() {
        return (rs, rowNum) -> {
            KnowledgeProjectVO vo = new KnowledgeProjectVO();
            vo.setProjectId(rs.getLong("project_id"));
            vo.setUserId(rs.getLong("user_id"));
            vo.setName(rs.getString("name"));
            vo.setDescription(rs.getString("description"));
            vo.setStatus(rs.getString("status"));
            vo.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            vo.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            return vo;
        };
    }

    private AuthUser requireUser() {
        AuthUser user = AuthUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }
        return user;
    }

    private String requireName(String value) {
        String name = trimToNull(value);
        if (name == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project name is required");
        }
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ReferenceWorkScope(Long projectId, Long workId, String title) {
    }
}
