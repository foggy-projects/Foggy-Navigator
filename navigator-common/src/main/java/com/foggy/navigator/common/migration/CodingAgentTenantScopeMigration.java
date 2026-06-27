package com.foggy.navigator.common.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Relaxes the legacy global unique index on coding_agents.agentId.
 *
 * Business/LangGraph upstream agents are resolved through an Open API tenant
 * context, so the database uniqueness boundary must match tenant + agentId.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodingAgentTenantScopeMigration implements DatabaseStartupMigration {

    private static final String TABLE = "coding_agents";
    private static final String COMPOSITE_INDEX = "uk_ca_tenant_agent_id";
    private static final String AGENT_PROFILE_COLUMN = "agent_profile";

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseMigrationSupport migrationSupport;

    @Override
    public String id() {
        return "startup-001-coding-agent-tenant-scope";
    }

    @Override
    public String description() {
        return "Ensure coding_agents tenant-scoped uniqueness and agent profile column";
    }

    @Override
    public void migrate() {
        if (!migrationSupport.tableExists(TABLE)) {
            return;
        }

        ensureAgentProfileColumn();

        String agentColumn = migrationSupport.findColumn(TABLE, "agent_id", "agentId").orElse(null);
        String tenantColumn = migrationSupport.findColumn(TABLE, "tenant_id", "tenantId").orElse(null);
        if (!StringUtils.hasText(agentColumn) || !StringUtils.hasText(tenantColumn)) {
            log.warn("Skip coding_agents tenant-scope migration: columns not found");
            return;
        }

        dropSingleColumnUniqueAgentIndexes(agentColumn);
        ensureTenantAgentUniqueIndex(tenantColumn, agentColumn);
    }

    private void ensureAgentProfileColumn() {
        Optional<String> existingColumn = migrationSupport.findColumn(TABLE, AGENT_PROFILE_COLUMN);
        if (existingColumn.isPresent()) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + migrationSupport.quoteIdentifier(TABLE)
                + " ADD COLUMN " + migrationSupport.quoteIdentifier(AGENT_PROFILE_COLUMN) + " TEXT NULL");
        log.info("Added coding_agents agent profile column: {}", AGENT_PROFILE_COLUMN);
    }

    private void dropSingleColumnUniqueAgentIndexes(String agentColumn) {
        List<String> indexNames = migrationSupport.singleColumnUniqueIndexes(TABLE, agentColumn);

        for (String indexName : indexNames) {
            if (COMPOSITE_INDEX.equals(indexName)) {
                continue;
            }
            jdbcTemplate.execute("ALTER TABLE " + migrationSupport.quoteIdentifier(TABLE)
                    + " DROP INDEX " + migrationSupport.quoteIdentifier(indexName));
            log.info("Dropped legacy global coding_agents agentId unique index: {}", indexName);
        }
    }

    private void ensureTenantAgentUniqueIndex(String tenantColumn, String agentColumn) {
        if (migrationSupport.indexExists(TABLE, COMPOSITE_INDEX)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + migrationSupport.quoteIdentifier(TABLE)
                + " ADD UNIQUE INDEX " + migrationSupport.quoteIdentifier(COMPOSITE_INDEX)
                + " (" + migrationSupport.quoteIdentifier(tenantColumn)
                + ", " + migrationSupport.quoteIdentifier(agentColumn) + ")");
        log.info("Created tenant-scoped coding_agents unique index: {}", COMPOSITE_INDEX);
    }
}
