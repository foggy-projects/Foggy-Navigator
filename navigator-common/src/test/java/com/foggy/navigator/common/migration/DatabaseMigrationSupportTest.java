package com.foggy.navigator.common.migration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseMigrationSupportTest {

    @Test
    void isMySqlReturnsFalseWhenDataSourceMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport support = new DatabaseMigrationSupport(jdbcTemplate);

        assertFalse(support.isMySql());
    }

    @Test
    void isMySqlDetectsMysqlProductName() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);

        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");

        DatabaseMigrationSupport support = new DatabaseMigrationSupport(jdbcTemplate);

        assertTrue(support.isMySql());
    }

    @Test
    void tableExistsUsesCurrentDatabaseInformationSchema() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("coding_agents")))
                .thenReturn(1);
        DatabaseMigrationSupport support = new DatabaseMigrationSupport(jdbcTemplate);

        assertTrue(support.tableExists("coding_agents"));
    }

    @Test
    void findColumnReturnsFirstMatchingCandidate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("coding_agents")))
                .thenReturn(List.of("tenantId", "agent_id"));
        DatabaseMigrationSupport support = new DatabaseMigrationSupport(jdbcTemplate);

        Optional<String> column = support.findColumn("coding_agents", "tenant_id", "tenantId");

        assertEquals(Optional.of("tenantId"), column);
        assertTrue(support.columnExists("coding_agents", "agent_id"));
    }

    @Test
    void indexExistsUsesInformationSchemaStatistics() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq("coding_agents"), eq("uk_ca_tenant_agent_id"))).thenReturn(1);
        DatabaseMigrationSupport support = new DatabaseMigrationSupport(jdbcTemplate);

        assertTrue(support.indexExists("coding_agents", "uk_ca_tenant_agent_id"));
    }

    @Test
    void singleColumnUniqueIndexesReturnsMatchingIndexes() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class),
                eq("coding_agents"), eq("agent_id"))).thenReturn(List.of("uk_agent_id"));
        DatabaseMigrationSupport support = new DatabaseMigrationSupport(jdbcTemplate);

        assertEquals(List.of("uk_agent_id"), support.singleColumnUniqueIndexes("coding_agents", "agent_id"));
    }

    @Test
    void quoteIdentifierEscapesBackticks() {
        DatabaseMigrationSupport support = new DatabaseMigrationSupport(mock(JdbcTemplate.class));

        assertEquals("`bad``name`", support.quoteIdentifier("bad`name"));
        assertEquals("bad``name", support.escapeIdentifier("bad`name"));
    }
}
