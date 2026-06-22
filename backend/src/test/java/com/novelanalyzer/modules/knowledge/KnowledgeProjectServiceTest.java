package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.knowledge.dto.KnowledgeProjectRequest;
import com.novelanalyzer.modules.knowledge.service.KnowledgeProjectService;
import com.novelanalyzer.modules.knowledge.vo.KnowledgeProjectVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeProjectServiceTest {

    @AfterEach
    void clearAuth() {
        AuthUserHolder.clear();
    }

    @Test
    void shouldCreateListRenameAndArchiveOwnProject() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectService service = new KnowledgeProjectService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));

        KnowledgeProjectRequest request = new KnowledgeProjectRequest();
        request.setName("都市脑洞新书");
        request.setDescription("扫榜后的开文项目");
        KnowledgeProjectVO created = service.create(request);

        assertThat(created.getProjectId()).isNotNull();
        assertThat(service.listMine()).extracting(KnowledgeProjectVO::getName).containsExactly("都市脑洞新书");

        KnowledgeProjectRequest rename = new KnowledgeProjectRequest();
        rename.setName("都市脑洞新书 v2");
        service.rename(created.getProjectId(), rename);
        assertThat(service.listMine()).extracting(KnowledgeProjectVO::getName).containsExactly("都市脑洞新书 v2");

        service.archive(created.getProjectId());
        assertThat(service.listMine()).isEmpty();
    }

    @Test
    void shouldKeepProjectListUserScoped() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectService service = new KnowledgeProjectService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer-a", Set.of("USER")));
        KnowledgeProjectRequest request = new KnowledgeProjectRequest();
        request.setName("A Project");
        service.create(request);

        AuthUserHolder.set(AuthUser.of(8L, "writer-b", Set.of("USER")));

        List<KnowledgeProjectVO> projects = service.listMine();

        assertThat(projects).isEmpty();
        assertThatThrownBy(() -> service.rename(1L, request))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    void shouldBindConversationToOneProjectOnly() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectService service = new KnowledgeProjectService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeProjectRequest firstRequest = new KnowledgeProjectRequest();
        firstRequest.setName("Project A");
        KnowledgeProjectRequest secondRequest = new KnowledgeProjectRequest();
        secondRequest.setName("Project B");
        KnowledgeProjectVO first = service.create(firstRequest);
        KnowledgeProjectVO second = service.create(secondRequest);

        service.bindConversation(first.getProjectId(), 7L, "conv-1");
        service.bindConversation(first.getProjectId(), 7L, "conv-1");

        Integer count = jdbcTemplate.queryForObject(
            "select count(1) from ai_project_conversation where project_id = ? and conversation_id = ?",
            Integer.class,
            first.getProjectId(),
            "conv-1"
        );
        assertThat(count).isEqualTo(1);
        assertThatThrownBy(() -> service.bindConversation(second.getProjectId(), 7L, "conv-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectOperationsOnArchivedProject() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeProjectService service = new KnowledgeProjectService(jdbcTemplate);
        AuthUserHolder.set(AuthUser.of(7L, "writer", Set.of("USER")));
        KnowledgeProjectRequest request = new KnowledgeProjectRequest();
        request.setName("Archived Project");
        KnowledgeProjectVO created = service.create(request);
        service.archive(created.getProjectId());

        KnowledgeProjectRequest rename = new KnowledgeProjectRequest();
        rename.setName("Should Not Rename");

        assertThatThrownBy(() -> service.rename(created.getProjectId(), rename))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
        assertThatThrownBy(() -> service.bindConversation(created.getProjectId(), 7L, "conv-archived"))
            .isInstanceOf(BusinessException.class)
            .extracting("resultCode")
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:project-test-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table ai_project (" +
            "project_id bigint auto_increment primary key," +
            "user_id bigint not null," +
            "name varchar(120) not null," +
            "description varchar(500)," +
            "status varchar(20) not null," +
            "created_at timestamp default current_timestamp," +
            "updated_at timestamp default current_timestamp)");
        jdbcTemplate.execute("create table ai_project_conversation (" +
            "id bigint auto_increment primary key," +
            "project_id bigint not null," +
            "user_id bigint not null," +
            "conversation_id varchar(80) not null," +
            "created_at timestamp default current_timestamp," +
            "unique(project_id, conversation_id))");
        return jdbcTemplate;
    }
}
