package com.novelanalyzer.modules.config.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

@Component
@Order(0)
public class PromptConfigSchemaCompatibilityRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptConfigSchemaCompatibilityRunner.class);
    private static final String TABLE_NAME = "prompt_config";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final PromptConfigService promptConfigService;

    public PromptConfigSchemaCompatibilityRunner(DataSource dataSource,
                                                 JdbcTemplate jdbcTemplate,
                                                 PromptConfigService promptConfigService) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.promptConfigService = promptConfigService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String jsonLikeColumnType = resolveJsonLikeColumnType();
        boolean tableExists = promptConfigTableExists();
        if (!tableExists) {
            return;
        }
        ensureColumnExists(TABLE_NAME, "input_json_schema",
            "ALTER TABLE prompt_config ADD COLUMN input_json_schema " + jsonLikeColumnType);
        ensureColumnExists(TABLE_NAME, "input_example_json",
            "ALTER TABLE prompt_config ADD COLUMN input_example_json " + jsonLikeColumnType);
        ensureColumnExists(TABLE_NAME, "output_json_schema",
            "ALTER TABLE prompt_config ADD COLUMN output_json_schema " + jsonLikeColumnType);
        ensureColumnExists(TABLE_NAME, "output_example_json",
            "ALTER TABLE prompt_config ADD COLUMN output_example_json " + jsonLikeColumnType);
        ensureColumnExists(TABLE_NAME, "post_process_type",
            "ALTER TABLE prompt_config ADD COLUMN post_process_type VARCHAR(50) DEFAULT NULL");
        ensureColumnExists(TABLE_NAME, "parse_config_json",
            "ALTER TABLE prompt_config ADD COLUMN parse_config_json " + jsonLikeColumnType);
        promptConfigService.backfillMissingDefaultContracts();
    }

    private String resolveJsonLikeColumnType() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            if (productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql")) {
                return "JSON DEFAULT NULL";
            }
        } catch (SQLException ex) {
            LOGGER.warn("failed to resolve prompt_config compatibility column type: {}", ex.getMessage());
        }
        return "CLOB DEFAULT NULL";
    }

    private void ensureColumnExists(String tableName, String columnName, String alterSql) {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, tableName)) {
                return;
            }
            if (columnExists(connection, tableName, columnName)) {
                return;
            }
        } catch (SQLException ex) {
            LOGGER.warn("schema compatibility check failed for {}.{}: {}", tableName, columnName, ex.getMessage());
            return;
        }

        try {
            jdbcTemplate.execute(alterSql);
            LOGGER.info("schema compatibility added column {}.{}", tableName, columnName);
        } catch (RuntimeException ex) {
            LOGGER.warn("schema compatibility alter failed for {}.{}: {}", tableName, columnName, ex.getMessage());
        }
    }

    private boolean promptConfigTableExists() {
        try (Connection connection = dataSource.getConnection()) {
            return tableExists(connection, TABLE_NAME);
        } catch (SQLException ex) {
            LOGGER.warn("schema compatibility table check failed for {}: {}", TABLE_NAME, ex.getMessage());
            return false;
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        return hasTable(metaData, connection.getCatalog(), connection.getSchema(), tableName)
            || hasTable(metaData, connection.getCatalog(), connection.getSchema(), tableName.toUpperCase(Locale.ROOT))
            || hasTable(metaData, connection.getCatalog(), connection.getSchema(), tableName.toLowerCase(Locale.ROOT));
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        return hasColumn(metaData, connection.getCatalog(), connection.getSchema(), tableName, columnName)
            || hasColumn(
            metaData,
            connection.getCatalog(),
            connection.getSchema(),
            tableName.toUpperCase(Locale.ROOT),
            columnName.toUpperCase(Locale.ROOT)
        )
            || hasColumn(
            metaData,
            connection.getCatalog(),
            connection.getSchema(),
            tableName.toLowerCase(Locale.ROOT),
            columnName.toLowerCase(Locale.ROOT)
        );
    }

    private boolean hasTable(DatabaseMetaData metaData, String catalog, String schema, String tableName) throws SQLException {
        try (ResultSet resultSet = metaData.getTables(catalog, schema, tableName, null)) {
            return resultSet.next();
        }
    }

    private boolean hasColumn(DatabaseMetaData metaData,
                              String catalog,
                              String schema,
                              String tableName,
                              String columnName) throws SQLException {
        try (ResultSet resultSet = metaData.getColumns(catalog, schema, tableName, columnName)) {
            return resultSet.next();
        }
    }
}
