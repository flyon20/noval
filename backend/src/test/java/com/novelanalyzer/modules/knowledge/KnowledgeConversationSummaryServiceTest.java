package com.novelanalyzer.modules.knowledge;

import com.novelanalyzer.modules.knowledge.service.KnowledgeConversationSummaryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeConversationSummaryServiceTest {

    @Test
    void shouldUpsertAndReadRollingConversationSummary() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeConversationSummaryService service = new KnowledgeConversationSummaryService(jdbcTemplate);

        service.updateSummary(7L, 900L, "conv-1", "用户要写三端一体都市脑洞。", "trace-1");
        service.updateSummary(7L, 900L, "conv-1", "用户确认主角底层职业和特效外包设定。", "trace-2");

        Optional<KnowledgeConversationSummaryService.ConversationSummary> summary = service.readSummary(7L, "conv-1");

        assertThat(summary).isPresent();
        assertThat(summary.get().userId()).isEqualTo(7L);
        assertThat(summary.get().projectId()).isEqualTo(900L);
        assertThat(summary.get().summary()).contains("特效外包");
        assertThat(summary.get().sourceTraceId()).isEqualTo("trace-2");
        assertThat(jdbcTemplate.queryForObject(
            "select count(1) from ai_conversation_summary where conversation_id = ?",
            Integer.class,
            "conv-1"
        )).isEqualTo(1);
    }

    @Test
    void shouldKeepConversationSummaryUserScoped() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        KnowledgeConversationSummaryService service = new KnowledgeConversationSummaryService(jdbcTemplate);

        service.updateSummary(7L, 900L, "conv-1", "用户A摘要", "trace-1");

        assertThat(service.readSummary(8L, "conv-1")).isEmpty();
    }

    private JdbcTemplate jdbcTemplate() {
        JdbcTemplate jdbcTemplate = KnowledgeProjectServiceTest.jdbcTemplate();
        jdbcTemplate.execute("create table ai_conversation_summary (" +
            "id bigint auto_increment primary key," +
            "conversation_id varchar(80) not null," +
            "user_id bigint not null," +
            "project_id bigint," +
            "summary clob not null," +
            "source_trace_id varchar(80)," +
            "created_at timestamp default current_timestamp," +
            "updated_at timestamp default current_timestamp," +
            "unique(conversation_id, user_id))");
        return jdbcTemplate;
    }
}
