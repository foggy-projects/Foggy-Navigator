package com.foggy.navigator.common.migration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodingAgentTenantScopeMigrationTest {

    @Test
    void migrateSkipsWhenTableMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.tableExists("coding_agents")).thenReturn(false);

        new CodingAgentTenantScopeMigration(jdbcTemplate, migrationSupport).migrate();

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void migrateAddsProfileColumnDropsLegacyIndexAndCreatesCompositeIndex() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.tableExists("coding_agents")).thenReturn(true);
        when(migrationSupport.findColumn("coding_agents", "agent_id", "agentId"))
                .thenReturn(Optional.of("agent_id"));
        when(migrationSupport.findColumn("coding_agents", "tenant_id", "tenantId"))
                .thenReturn(Optional.of("tenant_id"));
        when(migrationSupport.findColumn("coding_agents", "agent_profile"))
                .thenReturn(Optional.empty());
        when(migrationSupport.singleColumnUniqueIndexes("coding_agents", "agent_id"))
                .thenReturn(List.of("uk_agent_id"));
        when(migrationSupport.indexExists("coding_agents", "uk_ca_tenant_agent_id"))
                .thenReturn(false);
        when(migrationSupport.quoteIdentifier(anyString())).thenAnswer(invocation ->
                "`" + invocation.getArgument(0, String.class) + "`");

        new CodingAgentTenantScopeMigration(jdbcTemplate, migrationSupport).migrate();

        verify(jdbcTemplate).execute("ALTER TABLE `coding_agents` ADD COLUMN `agent_profile` TEXT NULL");
        verify(jdbcTemplate).execute("ALTER TABLE `coding_agents` DROP INDEX `uk_agent_id`");
        verify(jdbcTemplate).execute("ALTER TABLE `coding_agents` ADD UNIQUE INDEX `uk_ca_tenant_agent_id` (`tenant_id`, `agent_id`)");
    }
}
