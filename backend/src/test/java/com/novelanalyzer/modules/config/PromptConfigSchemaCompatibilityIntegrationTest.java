package com.novelanalyzer.modules.config;

import com.novelanalyzer.modules.config.service.PromptConfigService;
import com.novelanalyzer.modules.config.service.PromptConfigSchemaCompatibilityRunner;
import com.novelanalyzer.modules.config.vo.PromptConfigVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:promptcompatdb;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "app.security.rate-limit-per-minute=100"
    }
)
@Sql(
    scripts = {
        "classpath:sql/phase2-schema-h2.sql",
        "classpath:sql/phase3-schema-h2.sql",
        "classpath:sql/phase4-schema-h2.sql",
        "classpath:sql/phase2-data-h2.sql",
        "classpath:sql/phase4-data-h2.sql"
    },
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class PromptConfigSchemaCompatibilityIntegrationTest {

    @Autowired
    private PromptConfigService promptConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PromptConfigSchemaCompatibilityRunner promptConfigSchemaCompatibilityRunner;

    @Test
    void shouldReadPromptConfigWhenLegacySchemaMissesPhase5Columns() {
        Integer missingInputSchemaColumnCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(1)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'PROMPT_CONFIG' AND COLUMN_NAME = 'INPUT_JSON_SCHEMA'
                """,
            Integer.class
        );
        Integer missingInputExampleColumnCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(1)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'PROMPT_CONFIG' AND COLUMN_NAME = 'INPUT_EXAMPLE_JSON'
                """,
            Integer.class
        );

        assertThat(missingInputSchemaColumnCount).isZero();
        assertThat(missingInputExampleColumnCount).isZero();

        promptConfigSchemaCompatibilityRunner.run(new DefaultApplicationArguments(new String[0]));

        Integer inputSchemaColumnCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(1)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'PROMPT_CONFIG' AND COLUMN_NAME = 'INPUT_JSON_SCHEMA'
                """,
            Integer.class
        );
        Integer inputExampleColumnCount = jdbcTemplate.queryForObject(
            """
                SELECT COUNT(1)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'PROMPT_CONFIG' AND COLUMN_NAME = 'INPUT_EXAMPLE_JSON'
                """,
            Integer.class
        );

        PromptConfigVO config = promptConfigService.getByType("deconstruct");

        assertThat(inputSchemaColumnCount).isEqualTo(1);
        assertThat(inputExampleColumnCount).isEqualTo(1);
        assertThat(config.getPromptType()).isEqualTo("deconstruct");
        assertThat(config.getPromptName()).isEqualTo("default-deconstruct");
        assertThat(config.getInputJsonSchema()).contains("\"chapters\"");
        assertThat(config.getInputExampleJson()).contains("\"bookName\": \"测试书籍\"");
    }
}
