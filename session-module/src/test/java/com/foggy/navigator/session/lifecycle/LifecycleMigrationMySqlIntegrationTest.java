package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.*;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "arch001.mysql.integration", matches = "true")
class LifecycleMigrationMySqlIntegrationTest {
    private static final String FORWARD =
            "docs/migration/2026-07-30-arch-001-lifecycle-owner.sql";
    private static final String ROLLBACK =
            "docs/migration/2026-07-30-arch-001-lifecycle-owner-rollback.sql";
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
    void forwardJpaContractsAndRollbackFloorExecuteOnDisposableMySql()
            throws Exception {
        MySQLContainer<?> mysql = new MySQLContainer<>(
                DockerImageName.parse("mysql:8.0"));
        mysql.start();
        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            connection.createStatement().execute(
                    "create table arch001_legacy_sentinel "
                            + "(id bigint primary key, marker varchar(64) not null)");
            connection.createStatement().execute(
                    "insert into arch001_legacy_sentinel values "
                            + "(1, 'legacy-untouched')");
            execute(connection, FORWARD);
            execute(connection, FORWARD);
            for (String table : TABLES) {
                assertThat(tableExists(connection, table)).isTrue();
            }
            assertThat(scalar(connection,
                    "select marker from arch001_legacy_sentinel where id=1"))
                    .isEqualTo("legacy-untouched");
            assertColumn(connection, "lifecycle_facts",
                    "physical_worker_id", true, 128);
            assertColumn(connection, "task_lifecycle_snapshots",
                    "safe_binding_digest", true, 128);
            assertColumn(connection, "lifecycle_effect_outbox",
                    "aggregate_type", false, 32);
            assertColumn(connection, "task_terminal_tombstones",
                    "provider_task_id", false, 128);
            assertThat(indexExists(connection, "lifecycle_facts",
                    "uk_lf_idempotency", false)).isTrue();
            assertThat(indexExists(connection, "lifecycle_effect_outbox",
                    "uk_leo_idempotency", false)).isTrue();
            assertThat(indexExists(connection, "lifecycle_effect_outbox",
                    "idx_leo_state", true)).isTrue();
            assertThat(indexExists(connection,
                    "lifecycle_writer_exclusivity_references",
                    "uk_lwer_active", false)).isTrue();
            assertColumn(connection, "lifecycle_effect_outbox",
                    "aggregate_reference_id", true, 160);
            assertColumn(connection, "lifecycle_effect_outbox",
                    "controller_inventory_digest", true, 128);
            assertColumn(connection,
                    "lifecycle_writer_exclusivity_references",
                    "reference_id", false, 160);
            assertColumn(connection,
                    "lifecycle_writer_exclusivity_proofs",
                    "controller_inventory_digest", false, 128);
            validateJpa(mysql);

            execute(connection, ROLLBACK);
            for (String table : TABLES) {
                assertThat(tableExists(connection, table)).isFalse();
            }
            assertThat(scalar(connection,
                    "select marker from arch001_legacy_sentinel where id=1"))
                    .isEqualTo("legacy-untouched");

            assertRollbackBlocked(mysql, "worker_marker", """
                    insert into worker_lifecycle_snapshots(
                      physical_worker_id,ownership_mode,availability,
                      conflict_state,fact_cursor,policy_version,snapshot_json,
                      row_version,updated_at)
                    values('fixture-worker','ENFORCED','READY','NONE',0,
                      'fixture','{}',0,now(6))
                    """);
            assertRollbackBlocked(mysql, "session_marker", """
                    insert into session_lifecycle_snapshots(
                      session_id,ownership_mode,canonical_phase,
                      foreground_lane_state,availability,conflict_state,
                      row_version,updated_at)
                    values('fixture-session','ENFORCED','OPEN','FREE',
                      'READY','NONE',0,now(6))
                    """);
            assertRollbackBlocked(mysql, "task_marker", """
                    insert into task_lifecycle_snapshots(
                      task_id,ownership_mode,canonical_phase,availability,
                      conflict_state,cleanup_state,fact_cursor,policy_version,
                      snapshot_json,row_version,updated_at)
                    values('fixture-task','ENFORCED','OPEN','READY','NONE',
                      'NOT_REQUIRED',0,'fixture','{}',0,now(6))
                    """);
            assertRollbackBlocked(mysql, "writer_active", """
                    insert into lifecycle_writer_generations(
                      generation_id,minimum_owner_protocol,target_commit,
                      status,row_version)
                    values('fixture-generation',1,'fixture','ACTIVE',0)
                    """);
            assertRollbackBlocked(mysql, "writer_enforced", """
                    insert into lifecycle_writer_generations(
                      generation_id,minimum_owner_protocol,target_commit,
                      status,row_version)
                    values('fixture-generation',1,'fixture','ENFORCED',0)
                    """);
            assertRollbackBlocked(mysql, "unreleased_reference", """
                    insert into lifecycle_writer_exclusivity_references(
                      reference_id,proof_id,aggregate_type,aggregate_id,
                      acquired_at)
                    values('fixture-reference','fixture-proof','TASK',
                      'fixture-task',now(6))
                    """);
            for (String state : List.of(
                    "PREPARED", "CLAIMED", "EFFECT_STARTED")) {
                assertRollbackBlocked(
                        mysql, "outbox_" + state.toLowerCase(),
                        """
                        insert into lifecycle_effect_outbox(
                          effect_id,aggregate_type,aggregate_id,effect_type,
                          effect_class,effect_state,idempotency_key,
                          content_free_payload_json,created_at,row_version)
                        values('fixture-effect','TASK','fixture-task',
                          'TERMINATION_REQUEST','EXTERNAL_PROVIDER_ONCE','%s',
                          'fixture-key','{}',now(6),0)
                        """.formatted(state));
            }
        } finally {
            mysql.stop();
        }
    }

    private void assertRollbackBlocked(
            MySQLContainer<?> mysql,
            String databaseSuffix,
            String markerSql) throws Exception {
        String database = "arch001_" + databaseSuffix;
        String administrationUrl = mysql.getJdbcUrl().replaceFirst(
                "/[^/?]+([?].*)?$", "/mysql");
        try (Connection root = DriverManager.getConnection(
                administrationUrl, "root", mysql.getPassword())) {
            root.createStatement().execute(
                    "create database `" + database + "`");
            root.createStatement().execute(
                    "grant all privileges on `" + database
                            + "`.* to '" + mysql.getUsername() + "'@'%'");
        }
        String url = mysql.getJdbcUrl().replaceFirst(
                "/[^/?]+([?].*)?$", "/" + database);
        try (Connection connection = DriverManager.getConnection(
                url, mysql.getUsername(), mysql.getPassword())) {
            execute(connection, FORWARD);
            connection.createStatement().execute(markerSql);
            assertThatThrownBy(() -> execute(connection, ROLLBACK))
                    .hasMessage(
                            "ARCH001_ROLLBACK_BLOCKED_ENFORCEMENT_FLOOR");
            assertThat(tableExists(connection,
                    "lifecycle_effect_outbox")).isTrue();
        }
    }

    private void validateJpa(MySQLContainer<?> mysql) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class",
                        "com.mysql.cj.jdbc.Driver")
                .applySetting("hibernate.connection.url", mysql.getJdbcUrl())
                .applySetting("hibernate.connection.username", mysql.getUsername())
                .applySetting("hibernate.connection.password", mysql.getPassword())
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();
        try {
            SessionFactory factory = new MetadataSources(registry)
                    .addAnnotatedClass(LifecycleFactEntity.class)
                    .addAnnotatedClass(WorkerLifecycleSnapshotEntity.class)
                    .addAnnotatedClass(TaskLifecycleSnapshotEntity.class)
                    .addAnnotatedClass(SessionLifecycleSnapshotEntity.class)
                    .addAnnotatedClass(LifecycleEffectOutboxEntity.class)
                    .addAnnotatedClass(TaskTerminalTombstoneEntity.class)
                    .addAnnotatedClass(TaskTerminalCleanupPlanEntity.class)
                    .addAnnotatedClass(LifecycleWriterGenerationEntity.class)
                    .addAnnotatedClass(LifecycleWriterInstanceRegistrationEntity.class)
                    .addAnnotatedClass(LifecycleWriterProofEntity.class)
                    .addAnnotatedClass(LifecycleWriterProofReferenceEntity.class)
                    .addAnnotatedClass(WorkerLifecycleSentinelLeaseEntity.class)
                    .buildMetadata().buildSessionFactory();
            factory.close();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private void assertColumn(
            Connection connection,
            String table,
            String column,
            boolean nullable,
            int length) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select is_nullable, character_maximum_length
                from information_schema.columns
                where table_schema=database() and table_name=? and column_name=?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(nullable ? "YES" : "NO");
                assertThat(result.getInt(2)).isEqualTo(length);
            }
        }
    }

    private boolean indexExists(
            Connection connection,
            String table,
            String index,
            boolean nonUnique) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select non_unique from information_schema.statistics
                where table_schema=database() and table_name=? and index_name=?
                limit 1
                """)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1) == nonUnique;
            }
        }
    }

    private boolean tableExists(Connection connection, String table)
            throws SQLException {
        return scalar(connection,
                "select count(*) from information_schema.tables "
                        + "where table_schema=database() and table_name='" + table + "'")
                .equals("1");
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private void execute(Connection connection, String relativePath)
            throws Exception {
        for (String statement : statements(read(relativePath))) {
            try (Statement sql = connection.createStatement()) {
                sql.execute(statement);
            }
        }
    }

    static List<String> statements(String script) {
        List<String> result = new ArrayList<>();
        String delimiter = ";";
        StringBuilder current = new StringBuilder();
        for (String raw : script.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("--") || line.isEmpty()) continue;
            if (line.toUpperCase().startsWith("DELIMITER ")) {
                delimiter = line.substring("DELIMITER ".length()).trim();
                continue;
            }
            current.append(raw).append('\n');
            String accumulated = current.toString().trim();
            if (accumulated.endsWith(delimiter)) {
                result.add(accumulated.substring(
                        0, accumulated.length() - delimiter.length()).trim());
                current.setLength(0);
            }
        }
        if (!current.toString().trim().isEmpty()) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private String read(String relativePath) throws Exception {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return Files.readString(candidate);
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("MIGRATION_NOT_FOUND");
    }
}
