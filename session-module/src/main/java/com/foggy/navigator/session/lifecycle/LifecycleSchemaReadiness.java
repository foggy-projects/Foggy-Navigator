package com.foggy.navigator.session.lifecycle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LifecycleSchemaReadiness {

    public static final String ACTIVATION_DISABLED =
            "ENFORCED_DISABLED_PENDING_ACTIVATION_EVIDENCE";

    private static final List<String> REQUIRED_TABLES = List.of(
            "lifecycle_facts",
            "worker_lifecycle_snapshots",
            "task_lifecycle_snapshots",
            "session_lifecycle_snapshots",
            "lifecycle_effect_outbox",
            "task_terminal_tombstones",
            "task_terminal_cleanup_plan",
            "lifecycle_writer_generations",
            "lifecycle_writer_instance_registrations",
            "lifecycle_writer_exclusivity_proofs",
            "lifecycle_writer_exclusivity_references",
            "worker_lifecycle_sentinel_leases");

    private final JdbcTemplate jdbcTemplate;
    private final boolean activationEvidencePresent;

    public LifecycleSchemaReadiness(
            JdbcTemplate jdbcTemplate,
            @Value("${navigator.lifecycle.activation-evidence-present:false}")
            boolean activationEvidencePresent) {
        this.jdbcTemplate = jdbcTemplate;
        this.activationEvidencePresent = activationEvidencePresent;
    }

    public Readiness assess() {
        List<String> missing = new ArrayList<>();
        for (String table : REQUIRED_TABLES) {
            if (!tableExists(table)) missing.add(table);
        }
        if (!missing.isEmpty()) {
            return new Readiness(false, false,
                    List.of("LIFECYCLE_SCHEMA_NOT_READY"), missing);
        }
        if (!activationEvidencePresent) {
            return new Readiness(true, false,
                    List.of(ACTIVATION_DISABLED), List.of());
        }
        return new Readiness(true, true, List.of(), List.of());
    }

    private boolean tableExists(String table) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.tables "
                            + "where lower(table_name)=lower(?) "
                            + "and table_schema in (database(), 'PUBLIC')",
                    Integer.class,
                    table);
            return count != null && count > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public record Readiness(
            boolean shadowReady,
            boolean enforcedEnrollmentReady,
            List<String> reasonCodes,
            List<String> missingTables) {
        public Readiness {
            reasonCodes = List.copyOf(reasonCodes);
            missingTables = List.copyOf(missingTables);
        }
    }
}
