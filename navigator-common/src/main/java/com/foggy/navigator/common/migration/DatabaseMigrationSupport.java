package com.foggy.navigator.common.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Shared helpers for small startup migrations.
 *
 * This is intentionally narrower than a migration runner: it centralizes the
 * MySQL INFORMATION_SCHEMA checks used by existing idempotent migrations while
 * leaving large data moves under explicit operational scripts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseMigrationSupport {

    private final JdbcTemplate jdbcTemplate;

    public boolean isMySql() {
        try {
            DataSource dataSource = jdbcTemplate.getDataSource();
            if (dataSource == null) {
                return false;
            }
            String productName;
            try (Connection connection = dataSource.getConnection()) {
                productName = connection.getMetaData().getDatabaseProductName();
            }
            return StringUtils.hasText(productName)
                    && productName.toLowerCase(Locale.ROOT).contains("mysql");
        } catch (Exception e) {
            log.warn("Unable to detect database product for migration support: {}", e.getMessage());
            return false;
        }
    }

    public boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """, Integer.class, table);
        return count != null && count > 0;
    }

    public Optional<String> findColumn(String table, String... names) {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT COLUMN_NAME
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """, String.class, table);
        for (String name : names) {
            if (columns.contains(name)) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    public boolean columnExists(String table, String column) {
        return findColumn(table, column).isPresent();
    }

    public boolean indexExists(String table, String indexName) {
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                """, Integer.class, table, indexName);
        return existing != null && existing > 0;
    }

    public List<String> singleColumnUniqueIndexes(String table, String column) {
        return jdbcTemplate.queryForList("""
                SELECT s.INDEX_NAME
                FROM INFORMATION_SCHEMA.STATISTICS s
                WHERE s.TABLE_SCHEMA = DATABASE()
                  AND s.TABLE_NAME = ?
                  AND s.NON_UNIQUE = 0
                  AND s.INDEX_NAME <> 'PRIMARY'
                GROUP BY s.INDEX_NAME
                HAVING COUNT(*) = 1 AND MAX(s.COLUMN_NAME) = ?
                """, String.class, table, column);
    }

    public String quoteIdentifier(String identifier) {
        return "`" + escapeIdentifier(identifier) + "`";
    }

    public String escapeIdentifier(String identifier) {
        return identifier.replace("`", "``");
    }
}
