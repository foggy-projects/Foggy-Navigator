package com.foggy.navigator.common.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * Relaxes the legacy global unique index on coding_agents.agentId.
 *
 * Business/LangGraph upstream agents are resolved through an Open API tenant
 * context, so the database uniqueness boundary must match tenant + agentId.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodingAgentTenantScopeMigration {

    private static final String TABLE = "coding_agents";
    private static final String COMPOSITE_INDEX = "uk_ca_tenant_agent_id";

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        try {
            DataSource dataSource = jdbcTemplate.getDataSource();
            if (dataSource == null) {
                return;
            }
            String productName;
            try (Connection connection = dataSource.getConnection()) {
                productName = connection.getMetaData().getDatabaseProductName();
            }
            if (!StringUtils.hasText(productName) || !productName.toLowerCase().contains("mysql")) {
                return;
            }
            if (!tableExists()) {
                return;
            }

            String agentColumn = findColumn("agent_id", "agentId");
            String tenantColumn = findColumn("tenant_id", "tenantId");
            if (!StringUtils.hasText(agentColumn) || !StringUtils.hasText(tenantColumn)) {
                log.warn("Skip coding_agents tenant-scope migration: columns not found");
                return;
            }

            dropSingleColumnUniqueAgentIndexes(agentColumn);
            ensureTenantAgentUniqueIndex(tenantColumn, agentColumn);
        } catch (Exception e) {
            log.warn("Failed to migrate coding_agents tenant-scoped uniqueness: {}", e.getMessage());
        }
    }

    private boolean tableExists() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """, Integer.class, TABLE);
        return count != null && count > 0;
    }

    private String findColumn(String... names) {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT COLUMN_NAME
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """, String.class, TABLE);
        for (String name : names) {
            if (columns.contains(name)) {
                return name;
            }
        }
        return null;
    }

    private void dropSingleColumnUniqueAgentIndexes(String agentColumn) {
        List<String> indexNames = jdbcTemplate.queryForList("""
                SELECT s.INDEX_NAME
                FROM INFORMATION_SCHEMA.STATISTICS s
                WHERE s.TABLE_SCHEMA = DATABASE()
                  AND s.TABLE_NAME = ?
                  AND s.NON_UNIQUE = 0
                  AND s.INDEX_NAME <> 'PRIMARY'
                GROUP BY s.INDEX_NAME
                HAVING COUNT(*) = 1 AND MAX(s.COLUMN_NAME) = ?
                """, String.class, TABLE, agentColumn);

        for (String indexName : indexNames) {
            if (COMPOSITE_INDEX.equals(indexName)) {
                continue;
            }
            jdbcTemplate.execute("ALTER TABLE `" + TABLE + "` DROP INDEX `" + escapeIdentifier(indexName) + "`");
            log.info("Dropped legacy global coding_agents agentId unique index: {}", indexName);
        }
    }

    private void ensureTenantAgentUniqueIndex(String tenantColumn, String agentColumn) {
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                """, Integer.class, TABLE, COMPOSITE_INDEX);
        if (existing != null && existing > 0) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE `" + TABLE + "` ADD UNIQUE INDEX `" + COMPOSITE_INDEX
                + "` (`" + escapeIdentifier(tenantColumn) + "`, `" + escapeIdentifier(agentColumn) + "`)");
        log.info("Created tenant-scoped coding_agents unique index: {}", COMPOSITE_INDEX);
    }

    private static String escapeIdentifier(String identifier) {
        return identifier.replace("`", "``");
    }
}
