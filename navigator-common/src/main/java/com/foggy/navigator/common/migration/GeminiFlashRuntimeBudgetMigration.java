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

/**
 * Aligns the internal Gemini Flash alias with the upstream model context limit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiFlashRuntimeBudgetMigration {

    private static final String TABLE = "llm_model_config";
    private static final String MODEL_NAME = "gemini-3.5-flash-low";
    private static final String PRESET_KEY = "generic.1m";
    private static final String OVERRIDE_JSON = """
            {"max_input_tokens":970000,"auto_compact_input_token_threshold":900000,"max_output_tokens":65535,"prompt_reserve_output_tokens":65535}
            """.trim();

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
        } catch (Exception e) {
            log.warn("Failed to migrate Gemini Flash runtime budget: {}", e.getMessage());
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
}
