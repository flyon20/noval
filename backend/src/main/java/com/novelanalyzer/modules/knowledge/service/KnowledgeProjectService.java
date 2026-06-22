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
import java.util.List;

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
}
