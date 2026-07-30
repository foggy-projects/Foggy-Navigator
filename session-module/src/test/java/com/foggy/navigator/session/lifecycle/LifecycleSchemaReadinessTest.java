package com.foggy.navigator.session.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleSchemaReadinessTest {

    private static final List<String> TABLES = List.of(
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

    @Test
    void schemaMustBeCompleteAndRealActivationStaysFailClosed() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:readiness_" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(new LifecycleSchemaReadiness(jdbc, false).assess().shadowReady())
                .isFalse();
        for (String table : TABLES) {
            jdbc.execute("create table " + table + " (fixture_id integer)");
        }
        LifecycleSchemaReadiness.Readiness readiness =
                new LifecycleSchemaReadiness(jdbc, false).assess();
        assertThat(readiness.shadowReady()).isTrue();
        assertThat(readiness.enforcedEnrollmentReady()).isFalse();
        assertThat(readiness.reasonCodes())
                .containsExactly(LifecycleSchemaReadiness.ACTIVATION_DISABLED);
    }
}
