package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeProjectRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectMemoryService;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeProjectMemoryVO;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeProjectVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeProjectMemoryServiceTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldUpsertAndReadProjectMemoryByProjectAndUser() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectService projectService = new KnowledgeProjectService(jdbcTemplate);
        KnowledgeProjectMemoryService memoryService = new KnowledgeProjectMemoryService(jdbcTemplate, projectService);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeProjectVO project = createProject(projectService, "Book Project");

        memoryService.upsert(
            project.getProjectId(),
            7L,
            Map.of("genre", "urban fantasy", "styleConstraints", "no harem"),
            "trace-1"
        );
        memoryService.upsert(project.getProjectId(), 7L, Map.of("genre", "urban mystery"), "trace-2");

        KnowledgeProjectMemoryVO memory = memoryService.read(project.getProjectId(), 7L);

        assertThat(memory.getProjectId()).isEqualTo(project.getProjectId());
        assertThat(memory.getUserId()).isEqualTo(7L);
        assertThat(memory.getMemories())
            .containsEntry("genre", "urban mystery")
            .containsEntry("styleConstraints", "no harem");
    }

    @Test
    void shouldKeepProjectMemoryUserScoped() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectService projectService = new KnowledgeProjectService(jdbcTemplate);
        KnowledgeProjectMemoryService memoryService = new KnowledgeProjectMemoryService(jdbcTemplate, projectService);
        AuthUserHolder.set(AuthUser.of(7L, "writer-a", Set.of("USER")));
        KnowledgeProjectVO project = createProject(projectService, "A Project");

        memoryService.upsert(project.getProjectId(), 7L, Map.of("premise", "delivery runner hears future reviews"), "trace-1");

        assertThatThrownBy(() -> memoryService.read(project.getProjectId(), 8L))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
        assertThatThrownBy(() -> memoryService.upsert(project.getProjectId(), 8L, Map.of("genre", "wrong user"), "trace-2"))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    void shouldRejectArchivedProjectMemory() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectService projectService = new KnowledgeProjectService(jdbcTemplate);
        KnowledgeProjectMemoryService memoryService = new KnowledgeProjectMemoryService(jdbcTemplate, projectService);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeProjectVO project = createProject(projectService, "Archived Project");
        memoryService.upsert(project.getProjectId(), 7L, Map.of("genre", "urban"), "trace-1");

        projectService.archive(project.getProjectId());

        assertThatThrownBy(() -> memoryService.read(project.getProjectId(), 7L))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    private KnowledgeProjectVO createProject(KnowledgeProjectService projectService, String name) {
        KnowledgeProjectRequest request = new KnowledgeProjectRequest();
        request.setName(name);
        return projectService.create(request);
    }

    private JdbcTemplate jdbcTemplate() {
        JdbcTemplate jdbcTemplate = KnowledgeProjectServiceTest.jdbcTemplate();
        jdbcTemplate.execute("create table ai_project_memory (" +
            "id bigint auto_increment primary key," +
            "project_id bigint not null," +
            "user_id bigint not null," +
            "memory_key varchar(120) not null," +
            "memory_value clob," +
            "source_trace_id varchar(80)," +
            "created_at timestamp default current_timestamp," +
            "updated_at timestamp default current_timestamp," +
            "unique(project_id, memory_key))");
        return jdbcTemplate;
    }
}
