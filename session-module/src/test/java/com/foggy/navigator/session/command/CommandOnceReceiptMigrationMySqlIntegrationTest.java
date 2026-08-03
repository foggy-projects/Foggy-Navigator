package com.foggy.navigator.session.command;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.session.command.persistence.CommandOnceReceiptEntity;
import com.foggy.navigator.session.command.persistence.CommandOnceReceiptEntity.ReceiptState;
import com.foggy.navigator.session.command.repository.CommandOnceReceiptRepository;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(
        named = "navi.command.receipt.mysql.integration",
        matches = "true")
class CommandOnceReceiptMigrationMySqlIntegrationTest {

    private static final String MIGRATION =
            "docs/migration/2026-08-03-navi-core-command-once-receipts.sql";
    private static final String TABLE = "command_once_receipts";

    @Test
    void additiveReceiptMigrationIsExactIdempotentAndJpaValidOnDisposableMySql()
            throws Exception {
        String migration = read(MIGRATION);
        assertSingleAdditiveCreate(migration);
        verifyEntitySeams();

        MySQLContainer<?> mysql = new MySQLContainer<>(
                DockerImageName.parse("mysql:8.0.44"));
        mysql.start();
        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            assertThat(scalar(connection, "select version()"))
                    .startsWith("8.0.44");
            assertThat(connection.getCatalog()).isEqualTo(mysql.getDatabaseName());
            System.out.println("NAVI_CORE_COMMAND_RECEIPT_MYSQL image="
                    + mysql.getDockerImageName() + " database="
                    + mysql.getDatabaseName() + " isolated_container=true");

            connection.createStatement().execute("""
                    create table navi_core_b1_sentinel (
                        id bigint primary key,
                        marker varchar(64) not null
                    )
                    """);
            connection.createStatement().execute("""
                    insert into navi_core_b1_sentinel values (1, 'untouched')
                    """);

            execute(connection, migration);
            execute(connection, migration);

            assertThat(tableExists(connection, TABLE)).isTrue();
            assertThat(scalar(connection, "select count(*) from " + TABLE))
                    .isEqualTo("0");
            assertThat(scalar(connection, """
                    select marker from navi_core_b1_sentinel where id=1
                    """)).isEqualTo("untouched");
            assertExactTable(connection);
            assertExactColumns(connection);
            assertExactIndexes(connection);
            assertExactChecks(connection);
            validateJpa(mysql);
            verifyDatabaseGuards(connection);

            assertThat(scalar(connection, """
                    select marker from navi_core_b1_sentinel where id=1
                    """)).isEqualTo("untouched");
        } finally {
            mysql.stop();
        }
    }

    @Test
    void repositoryPublicSurfaceIsExactAndNonDestructive() {
        assertThat(Arrays.stream(CommandOnceReceiptRepository.class.getMethods())
                .map(this::publicMethodSignature)
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "saveAndFlush(CommandOnceReceiptEntity)->CommandOnceReceiptEntity",
                        "findByClientRequestId(String)->Optional",
                        "findByEffectAttemptId(String)->Optional",
                        "findByReceiptIdForUpdate(String)->Optional");
        assertThat(CrudRepository.class.isAssignableFrom(
                CommandOnceReceiptRepository.class)).isFalse();
        assertThat(JpaRepository.class.isAssignableFrom(
                CommandOnceReceiptRepository.class)).isFalse();
    }

    private String publicMethodSignature(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(","));
        return method.getName() + "(" + parameters + ")->"
                + method.getReturnType().getSimpleName();
    }

    private void verifyEntitySeams() {
        Instant issuedAt = Instant.ofEpochSecond(1_800_000_000L, 123_456_789);
        Instant notBefore = Instant.ofEpochSecond(1_800_000_000L, 223_456_789);
        Instant expiresAt = Instant.ofEpochSecond(1_800_000_030L, 323_456_789);
        CanonicalCommandEnvelope envelope = envelope(
                "request-entity", issuedAt, notBefore, expiresAt);
        LocalDateTime preparedAt = LocalDateTime.of(
                2026, 8, 3, 12, 0, 0, 987_654_321);

        CommandOnceReceiptEntity receipt = CommandOnceReceiptEntity.prepared(
                "a".repeat(64),
                envelope,
                "binding-v1",
                "b".repeat(64),
                "authorization-v1",
                "c".repeat(64),
                preparedAt);

        assertThat(receipt.getReceiptId()).isEqualTo("a".repeat(64));
        assertThat(receipt.getClientRequestId()).isEqualTo("request-entity");
        assertThat(receipt.getIdempotencyKey()).isEqualTo("idempotency-entity");
        assertThat(receipt.getClientAppReference()).isEqualTo("client-app-1");
        assertThat(receipt.getUpstreamReference()).isEqualTo("upstream-1");
        assertThat(receipt.getEffectScopeReference()).isEqualTo("effect-scope-1");
        assertThat(receipt.getBindingDigestVersion()).isEqualTo("binding-v1");
        assertThat(receipt.getBindingDigest()).isEqualTo("b".repeat(64));
        assertThat(receipt.getAuthorizationBindingDigestVersion())
                .isEqualTo("authorization-v1");
        assertThat(receipt.getAuthorizationBindingDigest()).isEqualTo("c".repeat(64));
        assertThat(receipt.getAuthorizationDecisionId()).isEqualTo("decision-entity");
        assertThat(receipt.getAuthorizationIssuedAtEpochSecond())
                .isEqualTo(issuedAt.getEpochSecond());
        assertThat(receipt.getAuthorizationIssuedAtNano()).isEqualTo(123_456_789);
        assertThat(receipt.getAuthorizationNotBeforeEpochSecond())
                .isEqualTo(notBefore.getEpochSecond());
        assertThat(receipt.getAuthorizationNotBeforeNano()).isEqualTo(223_456_789);
        assertThat(receipt.getAuthorizationExpiresAtEpochSecond())
                .isEqualTo(expiresAt.getEpochSecond());
        assertThat(receipt.getAuthorizationExpiresAtNano()).isEqualTo(323_456_789);
        assertThat(receipt.getAuthorizationIssuedAt()).isEqualTo(issuedAt);
        assertThat(receipt.getAuthorizationNotBefore()).isEqualTo(notBefore);
        assertThat(receipt.getAuthorizationExpiresAt()).isEqualTo(expiresAt);
        assertThat(receipt.getPreparedAt().getNano()).isEqualTo(987_654_000);
        assertThat(receipt.getState()).isEqualTo(ReceiptState.PREPARED);

        LocalDateTime startedAt = receipt.getPreparedAt().plusNanos(1_000);
        assertThatThrownBy(() -> receipt.beginEffect(
                "effect-too-early", receipt.getPreparedAt().minusNanos(1_000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(receipt.getState()).isEqualTo(ReceiptState.PREPARED);
        assertThat(receipt.getEffectAttemptId()).isNull();
        assertThat(receipt.getEffectStartedAt()).isNull();
        receipt.beginEffect("effect-entity", startedAt);
        assertThat(receipt.getState()).isEqualTo(ReceiptState.EFFECT_STARTED);
        assertThat(receipt.getEffectAttemptId()).isEqualTo("effect-entity");
        assertThatThrownBy(() -> receipt.beginEffect(
                "effect-second", startedAt.plusNanos(1_000)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> receipt.recordResult(
                "effect-wrong", "TASK:task-1", "COMMAND_RECORDED",
                startedAt.plusNanos(1_000)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> receipt.recordResult(
                "effect-entity", "TASK:too-early", "COMMAND_RECORDED",
                startedAt.minusNanos(1_000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(receipt.getState()).isEqualTo(ReceiptState.EFFECT_STARTED);
        assertThat(receipt.getOpaqueResultReference()).isNull();
        assertThat(receipt.getSafeCode()).isNull();
        assertThat(receipt.getResultRecordedAt()).isNull();

        receipt.recordResult(
                "effect-entity",
                "TASK:task-1",
                "COMMAND_RECORDED",
                startedAt.plusNanos(1_000));
        assertThat(receipt.getState()).isEqualTo(ReceiptState.RESULT_RECORDED);
        assertThat(receipt.getOpaqueResultReference()).isEqualTo("TASK:task-1");
        assertThat(receipt.getSafeCode()).isEqualTo("COMMAND_RECORDED");
        assertThatThrownBy(() -> receipt.markAmbiguous(
                "effect-entity", "OUTCOME_UNKNOWN", startedAt.plusNanos(2_000)))
                .isInstanceOf(IllegalStateException.class);

        CommandOnceReceiptEntity ambiguous = CommandOnceReceiptEntity.prepared(
                "d".repeat(64),
                envelope,
                "binding-v1",
                "e".repeat(64),
                "authorization-v1",
                "f".repeat(64),
                preparedAt);
        ambiguous.beginEffect("effect-ambiguous", startedAt);
        assertThatThrownBy(() -> ambiguous.markAmbiguous(
                "effect-ambiguous", "TOO_EARLY", startedAt.minusNanos(1_000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ambiguous.getState()).isEqualTo(ReceiptState.EFFECT_STARTED);
        assertThat(ambiguous.getSafeCode()).isNull();
        assertThat(ambiguous.getAmbiguousAt()).isNull();
        ambiguous.markAmbiguous(
                "effect-ambiguous", "OUTCOME_UNKNOWN", startedAt.plusNanos(1_000));
        assertThat(ambiguous.getState()).isEqualTo(ReceiptState.AMBIGUOUS);
        assertThat(ambiguous.getOpaqueResultReference()).isNull();
        assertThat(ambiguous.getSafeCode()).isEqualTo("OUTCOME_UNKNOWN");

        assertThat(Arrays.stream(CommandOnceReceiptEntity.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("set")))
                .isEmpty();
    }

    private CanonicalCommandEnvelope envelope(
            String requestId,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {
        CanonicalCommandEnvelope.CommandBinding binding =
                new CanonicalCommandEnvelope.CommandBinding(
                        CanonicalCommandEnvelope.CommandKind.CREATE,
                        new CanonicalCommandEnvelope.Ingress(
                                CanonicalCommandEnvelope.CommandIngress.A2A,
                                "workers",
                                "task.create"),
                        new CanonicalCommandEnvelope.Request(
                                requestId,
                                "idempotency-entity",
                                "correlation-entity"),
                        new CanonicalCommandEnvelope.Actor(
                                CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                                AuthorizationPrincipalType.NAVIGATOR_USER,
                                AuthorizationCredentialLane.NAVIGATOR_JWT,
                                "principal-fingerprint",
                                null),
                        new CanonicalCommandEnvelope.Ownership(
                                "tenant-1",
                                "owner-1",
                                "client-app-1",
                                "upstream-1"),
                        new CanonicalCommandEnvelope.Target(
                                CanonicalCommandEnvelope.TargetKind.TASK,
                                "task-1",
                                "logical-agent-1",
                                "codex-worker",
                                "worker-1",
                                "model-config-1",
                                "task-1",
                                "session-1"),
                        new CanonicalCommandEnvelope.Effect(
                                "task.create",
                                "effect-scope-1"));
        return new CanonicalCommandEnvelope(
                CanonicalCommandEnvelope.SCHEMA_VERSION,
                binding,
                new CanonicalCommandEnvelope.AuthorizationMetadata(
                        CanonicalCommandEnvelope.AUTHORIZATION_METADATA_SCHEMA_VERSION,
                        "decision-entity",
                        "policy-v1",
                        binding.request().correlationId(),
                        issuedAt,
                        notBefore,
                        expiresAt));
    }

    private void verifyDatabaseGuards(Connection connection) throws Exception {
        insertPrepared(
                connection, "1".repeat(64), "request-1", "shared-idempotency");

        assertThatThrownBy(() -> insertPrepared(
                connection, "2".repeat(64), "request-1", "changed-idempotency"))
                .isInstanceOf(SQLException.class);

        insertPrepared(
                connection, "3".repeat(64), "request-2", "shared-idempotency");
        assertThat(scalar(connection, "select count(*) from " + TABLE))
                .isEqualTo("2");

        connection.createStatement().executeUpdate("""
                update command_once_receipts
                   set receipt_state='EFFECT_STARTED',
                       effect_attempt_id='effect-shared',
                       effect_started_at=current_timestamp(6)
                 where receipt_id='%s'
                """.formatted("1".repeat(64)));
        assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                update command_once_receipts
                   set receipt_state='EFFECT_STARTED',
                       effect_attempt_id='effect-shared',
                       effect_started_at=current_timestamp(6)
                 where receipt_id='%s'
                """.formatted("3".repeat(64))))
                .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                update command_once_receipts
                   set actor_kind='SERVER_PROCESS'
                 where receipt_id='%s'
                """.formatted("3".repeat(64))))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                update command_once_receipts
                   set receipt_state='RESULT_RECORDED'
                 where receipt_id='%s'
                """.formatted("3".repeat(64))))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                update command_once_receipts
                   set authorization_not_before_nano=1000000000
                 where receipt_id='%s'
                """.formatted("3".repeat(64))))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                update command_once_receipts
                   set authorization_not_before_epoch_second=
                         authorization_expires_at_epoch_second,
                       authorization_not_before_nano=authorization_expires_at_nano
                 where receipt_id='%s'
                """.formatted("3".repeat(64))))
                .isInstanceOf(SQLException.class);

        connection.createStatement().executeUpdate("""
                update command_once_receipts
                   set receipt_state='RESULT_RECORDED',
                       opaque_result_reference='TASK:task-1',
                       safe_code='COMMAND_RECORDED',
                       result_recorded_at=current_timestamp(6)
                 where receipt_id='%s'
                """.formatted("1".repeat(64)));
        connection.createStatement().executeUpdate("""
                update command_once_receipts
                   set receipt_state='AMBIGUOUS',
                       effect_attempt_id='effect-ambiguous',
                       safe_code='OUTCOME_UNKNOWN',
                       effect_started_at=current_timestamp(6),
                       ambiguous_at=current_timestamp(6)
                 where receipt_id='%s'
                """.formatted("3".repeat(64)));

        assertThat(scalar(connection, """
                select count(*) from command_once_receipts
                 where receipt_state in ('RESULT_RECORDED','AMBIGUOUS')
                """)).isEqualTo("2");
    }

    private void insertPrepared(
            Connection connection,
            String receiptId,
            String clientRequestId,
            String idempotencyKey) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into command_once_receipts(
                    receipt_id, command_schema_version, command_kind,
                    command_ingress, client_surface, route_id,
                    client_request_id, idempotency_key, correlation_id,
                    actor_kind, principal_type, credential_lane,
                    principal_fingerprint, tenant_reference, owner_reference,
                    target_kind, target_id, action_id, effect_scope_reference,
                    authorization_metadata_schema_version,
                    authorization_decision_id, authorization_policy_version,
                    authorization_correlation_id,
                    authorization_issued_at_epoch_second,
                    authorization_issued_at_nano,
                    authorization_not_before_epoch_second,
                    authorization_not_before_nano,
                    authorization_expires_at_epoch_second,
                    authorization_expires_at_nano,
                    binding_digest_version, binding_digest,
                    authorization_binding_digest_version,
                    authorization_binding_digest,
                    receipt_state, prepared_at, row_version
                ) values (
                    ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,
                    ?,?,?,?,?,?,?
                )
                """)) {
            int parameter = 1;
            statement.setString(parameter++, receiptId);
            statement.setString(parameter++, CanonicalCommandEnvelope.SCHEMA_VERSION);
            statement.setString(parameter++, "CREATE");
            statement.setString(parameter++, "A2A");
            statement.setString(parameter++, "workers");
            statement.setString(parameter++, "task.create");
            statement.setString(parameter++, clientRequestId);
            statement.setString(parameter++, idempotencyKey);
            statement.setString(parameter++, "correlation-1");
            statement.setString(parameter++, "AUTHENTICATED_PRINCIPAL");
            statement.setString(parameter++, "NAVIGATOR_USER");
            statement.setString(parameter++, "NAVIGATOR_JWT");
            statement.setString(parameter++, "principal-fingerprint");
            statement.setString(parameter++, "tenant-1");
            statement.setString(parameter++, "owner-1");
            statement.setString(parameter++, "TASK");
            statement.setString(parameter++, "task-1");
            statement.setString(parameter++, "task.create");
            statement.setString(parameter++, "effect-scope-1");
            statement.setString(
                    parameter++,
                    CanonicalCommandEnvelope.AUTHORIZATION_METADATA_SCHEMA_VERSION);
            statement.setString(parameter++, "decision-" + clientRequestId);
            statement.setString(parameter++, "policy-v1");
            statement.setString(parameter++, "correlation-1");
            statement.setLong(parameter++, 1_800_000_000L);
            statement.setInt(parameter++, 123_456_789);
            statement.setLong(parameter++, 1_800_000_000L);
            statement.setInt(parameter++, 223_456_789);
            statement.setLong(parameter++, 1_800_000_030L);
            statement.setInt(parameter++, 323_456_789);
            statement.setString(parameter++, "binding-v1");
            statement.setString(parameter++, "a".repeat(64));
            statement.setString(parameter++, "authorization-v1");
            statement.setString(parameter++, "b".repeat(64));
            statement.setString(parameter++, "PREPARED");
            statement.setObject(parameter++, LocalDateTime.of(2026, 8, 3, 12, 0));
            statement.setLong(parameter, 0L);
            statement.executeUpdate();
        }
    }

    private void assertSingleAdditiveCreate(String script) {
        List<String> statements = statements(script);
        assertThat(statements).hasSize(1);
        String normalized = " " + statements.get(0)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ") + " ";
        assertThat(normalized).startsWith(" create table if not exists ");
        assertThat(occurrences(normalized, " create table if not exists ")).isEqualTo(1);
        assertThat(normalized).doesNotContain(
                " alter table ",
                " insert into ",
                " update ",
                " delete from ",
                " drop table ",
                " truncate ",
                " text ",
                " json ",
                " blob ");
    }

    private int occurrences(String value, String token) {
        int count = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(token, cursor)) >= 0) {
            count++;
            cursor += token.length();
        }
        return count;
    }

    private void assertExactTable(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select engine, table_collation
                  from information_schema.tables
                 where table_schema=database() and table_name=?
                """)) {
            statement.setString(1, TABLE);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("InnoDB");
                assertThat(result.getString(2)).isEqualTo("utf8mb4_0900_bin");
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void assertExactColumns(Connection connection) throws SQLException {
        Map<String, ColumnSpec> actual = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
                select column_name, data_type, column_type, is_nullable,
                       character_maximum_length, collation_name,
                       datetime_precision
                  from information_schema.columns
                 where table_schema=database() and table_name=?
                 order by ordinal_position
                """)) {
            statement.setString(1, TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long length = result.getLong(5);
                    Long nullableLength = result.wasNull() ? null : length;
                    int precision = result.getInt(7);
                    Integer nullablePrecision = result.wasNull() ? null : precision;
                    actual.put(
                            result.getString(1),
                            new ColumnSpec(
                                    result.getString(2),
                                    result.getString(3),
                                    result.getString(4),
                                    nullableLength,
                                    result.getString(6),
                                    nullablePrecision));
                }
            }
        }
        assertThat(actual).isEqualTo(expectedColumns());
        assertThat(actual.keySet())
                .noneMatch(name -> name.matches(
                        ".*(payload|body|message|error_text|header|token|secret|path|url).*"));
        assertThat(actual.values())
                .extracting(ColumnSpec::dataType)
                .doesNotContain("text", "tinytext", "mediumtext", "longtext", "json",
                        "blob", "tinyblob", "mediumblob", "longblob");
    }

    private Map<String, ColumnSpec> expectedColumns() {
        Map<String, ColumnSpec> columns = new LinkedHashMap<>();
        varchar(columns, "receipt_id", 64, false);
        varchar(columns, "command_schema_version", 64, false);
        varchar(columns, "command_kind", 32, false);
        varchar(columns, "command_ingress", 32, false);
        varchar(columns, "client_surface", 128, false);
        varchar(columns, "route_id", 256, false);
        varchar(columns, "client_request_id", 256, false);
        varchar(columns, "idempotency_key", 256, false);
        varchar(columns, "correlation_id", 256, false);
        varchar(columns, "actor_kind", 32, false);
        varchar(columns, "principal_type", 64, true);
        varchar(columns, "credential_lane", 64, true);
        varchar(columns, "principal_fingerprint", 256, true);
        varchar(columns, "server_process_authority_reference", 256, true);
        varchar(columns, "tenant_reference", 256, false);
        varchar(columns, "owner_reference", 256, false);
        varchar(columns, "client_app_reference", 256, true);
        varchar(columns, "upstream_reference", 256, true);
        varchar(columns, "target_kind", 32, false);
        varchar(columns, "target_id", 256, false);
        varchar(columns, "logical_agent_id", 256, true);
        varchar(columns, "provider_type", 256, true);
        varchar(columns, "physical_worker_id", 256, true);
        varchar(columns, "model_config_id", 256, true);
        varchar(columns, "task_id", 256, true);
        varchar(columns, "session_id", 256, true);
        varchar(columns, "action_id", 256, false);
        varchar(columns, "effect_scope_reference", 256, false);
        varchar(columns, "authorization_metadata_schema_version", 64, false);
        varchar(columns, "authorization_decision_id", 256, false);
        varchar(columns, "authorization_policy_version", 256, false);
        varchar(columns, "authorization_correlation_id", 256, false);
        number(columns, "authorization_issued_at_epoch_second", "bigint");
        number(columns, "authorization_issued_at_nano", "int");
        number(columns, "authorization_not_before_epoch_second", "bigint");
        number(columns, "authorization_not_before_nano", "int");
        number(columns, "authorization_expires_at_epoch_second", "bigint");
        number(columns, "authorization_expires_at_nano", "int");
        varchar(columns, "binding_digest_version", 32, false);
        varchar(columns, "binding_digest", 64, false);
        varchar(columns, "authorization_binding_digest_version", 32, false);
        varchar(columns, "authorization_binding_digest", 64, false);
        varchar(columns, "receipt_state", 32, false);
        varchar(columns, "effect_attempt_id", 64, true);
        varchar(columns, "opaque_result_reference", 320, true);
        varchar(columns, "safe_code", 128, true);
        dateTime(columns, "prepared_at", false);
        dateTime(columns, "effect_started_at", true);
        dateTime(columns, "result_recorded_at", true);
        dateTime(columns, "ambiguous_at", true);
        number(columns, "row_version", "bigint");
        return columns;
    }

    private void varchar(
            Map<String, ColumnSpec> columns,
            String name,
            long length,
            boolean nullable) {
        columns.put(
                name,
                new ColumnSpec(
                        "varchar",
                        "varchar(" + length + ")",
                        nullable ? "YES" : "NO",
                        length,
                        "utf8mb4_0900_bin",
                        null));
    }

    private void number(
            Map<String, ColumnSpec> columns,
            String name,
            String type) {
        columns.put(name, new ColumnSpec(type, type, "NO", null, null, null));
    }

    private void dateTime(
            Map<String, ColumnSpec> columns,
            String name,
            boolean nullable) {
        columns.put(
                name,
                new ColumnSpec(
                        "datetime",
                        "datetime(6)",
                        nullable ? "YES" : "NO",
                        null,
                        null,
                        6));
    }

    private void assertExactIndexes(Connection connection) throws SQLException {
        List<IndexRow> actual = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                select index_name, non_unique, seq_in_index, column_name
                  from information_schema.statistics
                 where table_schema=database() and table_name=?
                 order by index_name, seq_in_index
                """)) {
            statement.setString(1, TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(new IndexRow(
                            result.getString(1),
                            result.getBoolean(2),
                            result.getInt(3),
                            result.getString(4)));
                }
            }
        }
        assertThat(actual).containsExactly(
                new IndexRow("PRIMARY", false, 1, "receipt_id"),
                new IndexRow("uk_cor_client_request", false, 1, "client_request_id"),
                new IndexRow("uk_cor_effect_attempt", false, 1, "effect_attempt_id"));
    }

    private void assertExactChecks(Connection connection) throws SQLException {
        Set<String> checks = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement("""
                select constraint_name
                  from information_schema.table_constraints
                 where table_schema=database() and table_name=?
                   and constraint_type='CHECK'
                 order by constraint_name
                """)) {
            statement.setString(1, TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) checks.add(result.getString(1));
            }
        }
        assertThat(checks).containsExactlyInAnyOrder(
                "chk_cor_command_kind",
                "chk_cor_command_ingress",
                "chk_cor_target_kind",
                "chk_cor_actor_shape",
                "chk_cor_auth_correlation",
                "chk_cor_auth_time",
                "chk_cor_state_shape");
    }

    private void validateJpa(MySQLContainer<?> mysql) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "com.mysql.cj.jdbc.Driver")
                .applySetting("hibernate.connection.url", mysql.getJdbcUrl())
                .applySetting("hibernate.connection.username", mysql.getUsername())
                .applySetting("hibernate.connection.password", mysql.getPassword())
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting(
                        "hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .build();
        try {
            SessionFactory factory = new MetadataSources(registry)
                    .addAnnotatedClass(CommandOnceReceiptEntity.class)
                    .buildMetadata()
                    .buildSessionFactory();
            factory.close();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select count(*) from information_schema.tables
                 where table_schema=database() and table_name=?
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private void execute(Connection connection, String script) throws SQLException {
        for (String statement : statements(script)) {
            try (Statement sql = connection.createStatement()) {
                sql.execute(statement);
            }
        }
    }

    static List<String> statements(String script) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : script.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("--")) continue;
            current.append(raw).append('\n');
            String accumulated = current.toString().trim();
            if (accumulated.endsWith(";")) {
                result.add(accumulated.substring(0, accumulated.length() - 1).trim());
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
        throw new IllegalStateException("COMMAND_RECEIPT_MIGRATION_NOT_FOUND");
    }

    private record ColumnSpec(
            String dataType,
            String columnType,
            String nullable,
            Long characterLength,
            String collation,
            Integer datetimePrecision) {
    }

    private record IndexRow(
            String name,
            boolean nonUnique,
            int sequence,
            String column) {
    }
}
