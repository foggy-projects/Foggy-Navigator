package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Lob;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessTaskScopedTokenSchemaPreflightTest {

    @Test
    void validate_failsClosedWhenRequiredColumnIsMissing() throws SQLException {
        DataSource dataSource = dataSource("missing-column");
        createTable(dataSource, false);
        createIndexes(dataSource, true);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new BusinessTaskScopedTokenSchemaPreflight(dataSource).validate()
        );

        assertTrue(error.getMessage().contains("missing columns [issued_at]"));
        assertTrue(error.getMessage().contains("2026-07-28-task-scoped-caller-provenance.sql"));
    }

    @Test
    void validate_failsClosedWhenRequiredIndexIsMissing() throws SQLException {
        DataSource dataSource = dataSource("missing-index");
        createTable(dataSource, true);
        createIndexes(dataSource, false);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new BusinessTaskScopedTokenSchemaPreflight(dataSource).validate()
        );

        assertTrue(error.getMessage().contains("idx_biz_token_tenant_worker_task"));
    }

    @Test
    void validate_failsClosedWhenTokenIdIsNotUnique() throws SQLException {
        DataSource dataSource = dataSource("missing-unique-token-id");
        createTable(dataSource, true);
        createIndexes(dataSource, true, false);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new BusinessTaskScopedTokenSchemaPreflight(dataSource).validate()
        );

        assertTrue(error.getMessage().contains("unique token_id"));
    }

    @Test
    void validate_acceptsRequiredColumnsAndIndexes() throws SQLException {
        DataSource dataSource = dataSource("compatible");
        createTable(dataSource, true);
        createIndexes(dataSource, true, true);

        assertDoesNotThrow(() -> new BusinessTaskScopedTokenSchemaPreflight(dataSource).validate());
    }

    @Test
    void validate_acceptsH2LongTextAliasGeneratedForMigrationCompatibleEntity() throws SQLException {
        DataSource dataSource = dataSource("compatible-h2-longtext");
        createTable(dataSource, true, "LONGTEXT NOT NULL");
        createIndexes(dataSource, true, true);

        assertDoesNotThrow(() -> new BusinessTaskScopedTokenSchemaPreflight(dataSource).validate());
    }

    @Test
    void validate_failsClosedWhenFunctionScopeColumnIsUndersized() throws SQLException {
        DataSource dataSource = dataSource("function-scope-undersized");
        createTable(dataSource, true, "VARCHAR(64) NOT NULL");
        createIndexes(dataSource, true, true);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new BusinessTaskScopedTokenSchemaPreflight(dataSource).validate()
        );

        assertTrue(error.getMessage().contains("invalid column definitions [function_scope_json"));
        assertTrue(error.getMessage().contains("2026-07-28-task-scoped-caller-provenance.sql"));
    }

    @Test
    void validate_failsClosedWhenFunctionScopeColumnIsNullable() throws SQLException {
        DataSource dataSource = dataSource("function-scope-nullable");
        createTable(dataSource, true, "CLOB");
        createIndexes(dataSource, true, true);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new BusinessTaskScopedTokenSchemaPreflight(dataSource).validate()
        );

        assertTrue(error.getMessage().contains("invalid column definitions [function_scope_json"));
    }

    @Test
    void functionScopeEntityExplicitlyDeclaresLongTextMigrationContract() throws NoSuchFieldException {
        Field field = BusinessTaskScopedTokenEntity.class.getDeclaredField("functionScopeJson");
        Column column = field.getAnnotation(Column.class);

        assertAll(
                () -> assertNotNull(column),
                () -> assertEquals("LONGTEXT", column.columnDefinition()),
                () -> assertFalse(column.nullable()),
                () -> assertFalse(field.isAnnotationPresent(Lob.class))
        );
    }

    private DataSource dataSource(String databaseName) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    private void createTable(DataSource dataSource, boolean withIssuedAt) throws SQLException {
        createTable(dataSource, withIssuedAt, "CLOB NOT NULL");
    }

    private void createTable(DataSource dataSource, boolean withIssuedAt, String functionScopeColumnDefinition) throws SQLException {
        String issuedAt = withIssuedAt ? ", issued_at TIMESTAMP NOT NULL" : "";
        execute(dataSource, ("""
                CREATE TABLE business_task_scoped_token (
                    id BIGINT PRIMARY KEY,
                    token_id VARCHAR(64) NOT NULL,
                    token_hash VARCHAR(128) NOT NULL,
                    task_id VARCHAR(64) NOT NULL,
                    tenant_id VARCHAR(64) NOT NULL,
                    worker_task_id VARCHAR(64),
                    worker_session_id VARCHAR(64),
                    session_id VARCHAR(64) NOT NULL,
                    client_app_id VARCHAR(64) NOT NULL,
                    upstream_user_id VARCHAR(128),
                    navigator_effective_user_id VARCHAR(64) NOT NULL,
                    navigator_instance_id VARCHAR(128),
                    caller_authority_type VARCHAR(48),
                    caller_credential_id VARCHAR(64),
                    caller_access_token_id VARCHAR(64),
                    skill_id VARCHAR(128),
                    worker_pool_id VARCHAR(64) NOT NULL,
                    model_config_id VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    row_version BIGINT NOT NULL,
                    token_version INT NOT NULL,
                    generation INT NOT NULL,
                    audience VARCHAR(64) NOT NULL,
                    identity_assurance VARCHAR(64) NOT NULL,
                    function_scope_json %s,
                    worker_id VARCHAR(128),
                    worker_lease_id VARCHAR(128),
                    revoked_at TIMESTAMP,
                    revoked_by VARCHAR(128),
                    revoke_reason VARCHAR(512),
                    expires_at TIMESTAMP NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                %s)""").formatted(functionScopeColumnDefinition, issuedAt));
    }

    private void createIndexes(DataSource dataSource, boolean withWorkerTaskIndex) throws SQLException {
        createIndexes(dataSource, withWorkerTaskIndex, true);
    }

    private void createIndexes(DataSource dataSource, boolean withWorkerTaskIndex, boolean uniqueTokenId) throws SQLException {
        if (uniqueTokenId) {
            execute(dataSource, "CREATE UNIQUE INDEX uk_biz_token_token_id ON business_task_scoped_token (token_id)");
        }
        execute(dataSource, "CREATE INDEX idx_biz_token_task ON business_task_scoped_token (task_id)");
        if (withWorkerTaskIndex) {
            execute(dataSource,
                    "CREATE INDEX idx_biz_token_tenant_worker_task ON business_task_scoped_token (tenant_id, worker_task_id)");
        }
    }

    private void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
