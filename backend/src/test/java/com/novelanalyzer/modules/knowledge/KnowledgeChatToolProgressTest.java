package com.novelanalyzer.modules.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelanalyzer.modules.knowledge.service.KnowledgeChatToolProgress;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChatToolProgressTest {
    @Test
    void shouldBoundItemsAndNeverReopenOrRewriteATerminal() throws Exception {
        List<Map<String, Object>> items = List.of();
        for (int index = 0; index < 65; index++) {
            items = KnowledgeChatToolProgress.merge(items, item(index, "running"));
        }
        assertThat(items).hasSize(64);
        items = KnowledgeChatToolProgress.merge(items, item(64, "unknown"));
        items = KnowledgeChatToolProgress.merge(items, item(64, "running"));
        items = KnowledgeChatToolProgress.merge(items, item(64, "completed"));
        assertThat(items.get(63)).containsEntry("itemStatus", "unknown");
        String json = new ObjectMapper().writeValueAsString(Map.of("schemaVersion", 1, "items", items));
        assertThat(KnowledgeChatToolProgress.readSnapshot(json)).isEqualTo(items);
        assertThat(KnowledgeChatToolProgress.readSnapshot(null)).isEmpty();
        assertThat(KnowledgeChatToolProgress.readSnapshot("{\"schemaVersion\":2,\"items\":[]}")).isEmpty();
    }

    @Test
    void shouldApplyAdditiveSnapshotMigrationTwiceWithoutChangingExistingRunData() {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:tool-progress-migration;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table ai_chat_run(run_id varchar(64) primary key, answer clob)");
        jdbc.update("insert into ai_chat_run(run_id, answer) values('existing', 'answer')");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("sql/phase31-agent-tool-progress-h2.sql"));
        DatabasePopulatorUtils.execute(migration, dataSource);
        jdbc.update("update ai_chat_run set tool_progress_json = '{}' where run_id = 'existing'");
        DatabasePopulatorUtils.execute(migration, dataSource);
        assertThat(jdbc.queryForObject("select answer from ai_chat_run where run_id = 'existing'", String.class))
            .isEqualTo("answer");
        assertThat(jdbc.queryForObject("select tool_progress_json from ai_chat_run where run_id = 'existing'", String.class))
            .isEqualTo("{}");
    }

    private Map<String, Object> item(int index, String status) {
        return Map.of("schemaVersion", 1, "itemId", "tool_" + String.format("%032x", index),
            "itemType", "tool", "itemStatus", status, "toolName", "knowledge.search");
    }
}
