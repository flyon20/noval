package com.novelanalyzer.modules.crawler.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

@Component
public class CrawlerSchemaCompatibilityRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrawlerSchemaCompatibilityRunner.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public CrawlerSchemaCompatibilityRunner(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureColumnExists("crawl_chapter", "source_word_count",
            "ALTER TABLE crawl_chapter ADD COLUMN source_word_count INT DEFAULT NULL");
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
