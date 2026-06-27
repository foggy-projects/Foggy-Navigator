package com.foggy.navigator.common.migration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiFlashRuntimeBudgetMigrationTest {

    @Test
    void migrateSkipsWhenTableMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.tableExists("llm_model_config")).thenReturn(false);

        new GeminiFlashRuntimeBudgetMigration(jdbcTemplate, migrationSupport).migrate();

        verify(jdbcTemplate, never()).update(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void migrateUpdatesGeminiFlashRuntimeBudgetWhenTableExists() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.tableExists("llm_model_config")).thenReturn(true);
        when(jdbcTemplate.update(anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        new GeminiFlashRuntimeBudgetMigration(jdbcTemplate, migrationSupport).migrate();

        verify(jdbcTemplate).update(contains("UPDATE llm_model_config"),
                eq("generic.1m"),
                contains("\"max_input_tokens\":970000"),
                eq("gemini-3.5-flash-low"));
    }
}
