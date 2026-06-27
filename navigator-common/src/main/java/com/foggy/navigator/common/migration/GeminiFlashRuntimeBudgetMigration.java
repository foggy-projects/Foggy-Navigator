package com.foggy.navigator.common.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Aligns the internal Gemini Flash alias with the upstream model context limit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiFlashRuntimeBudgetMigration implements DatabaseStartupMigration {

    private static final String TABLE = "llm_model_config";
    private static final String MODEL_NAME = "gemini-3.5-flash-low";
    private static final String PRESET_KEY = "generic.1m";
    private static final String OVERRIDE_JSON = """
            {"max_input_tokens":970000,"auto_compact_input_token_threshold":900000,"max_output_tokens":65535,"prompt_reserve_output_tokens":65535}
            """.trim();

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseMigrationSupport migrationSupport;

    @Override
    public String id() {
        return "startup-002-gemini-flash-runtime-budget";
    }

    @Override
    public String description() {
        return "Align Gemini Flash runtime budget with 1m context preset";
    }

    @Override
    public void migrate() {
        if (!migrationSupport.tableExists(TABLE)) {
            return;
        }

        int updated = jdbcTemplate.update("""
                UPDATE llm_model_config
                SET runtime_budget_preset_key = ?,
                    runtime_budget_override_json = ?,
                    updated_at = NOW(6)
                WHERE model_name = ?
                  AND worker_backend = 'LANGGRAPH_BIZ'
                  AND (
                      runtime_budget_preset_key IS NULL
                      OR runtime_budget_preset_key = ''
                      OR runtime_budget_preset_key = 'generic.128k'
                  )
                """, PRESET_KEY, OVERRIDE_JSON, MODEL_NAME);
        if (updated > 0) {
            log.info("Migrated {} Gemini Flash model config(s) to {} runtime budget", updated, PRESET_KEY);
        }
    }
}
