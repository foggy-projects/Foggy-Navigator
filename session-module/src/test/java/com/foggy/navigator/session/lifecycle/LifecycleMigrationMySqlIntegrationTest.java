package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.*;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import com.foggy.navigator.session.lifecycle.repository.*;
import com.foggy.navigator.spi.lifecycle.NormalizedLifecycleFact;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePort;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleReadiness;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "arch001.mysql.integration", matches = "true")
class LifecycleMigrationMySqlIntegrationTest {
    private static final String FORWARD =
            "docs/migration/2026-07-30-arch-001-lifecycle-owner.sql";
    private static final String ROLLBACK =
            "docs/migration/2026-07-30-arch-001-lifecycle-owner-rollback.sql";
    private static final String THIRD_REMEDIATION =
            "docs/migration/2026-07-31-arch-001-third-remediation.sql";
    private static final String ACTIVATION_READINESS =
            "docs/migration/2026-08-01-arch-001-activation-readiness.sql";
    private static final String BOUNDED_LOCAL_DEVELOPMENT =
            "docs/migration/2026-08-02-arch-001-bounded-local-development-activation.sql";
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
            "worker_lifecycle_sentinel_leases",
            "lifecycle_activation_targets");

    @Test
    void forwardJpaContractsAndRollbackFloorExecuteOnDisposableMySql()
            throws Exception {
        MySQLContainer<?> mysql = new MySQLContainer<>(
                DockerImageName.parse("mysql:8.0.44"));
        mysql.start();
        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            assertThat(scalar(connection, "select version()"))
                    .startsWith("8.0.44");
            assertThat(connection.getCatalog()).isEqualTo(mysql.getDatabaseName());
            System.out.println("ARCH001_ACTIVATION_MYSQL image="
                    + mysql.getDockerImageName() + " database="
                    + mysql.getDatabaseName() + " isolated_container=true");
            connection.createStatement().execute(
                    "create table arch001_legacy_sentinel "
                            + "(id bigint primary key, marker varchar(64) not null)");
            connection.createStatement().execute(
                    "insert into arch001_legacy_sentinel values "
                            + "(1, 'legacy-untouched')");
            execute(connection, FORWARD);
            execute(connection, FORWARD);
            execute(connection, THIRD_REMEDIATION);
            execute(connection, THIRD_REMEDIATION);
            execute(connection, ACTIVATION_READINESS);
            execute(connection, ACTIVATION_READINESS);
            execute(connection, BOUNDED_LOCAL_DEVELOPMENT);
            execute(connection, BOUNDED_LOCAL_DEVELOPMENT);
            for (String table : TABLES) {
                assertThat(tableExists(connection, table)).isTrue();
            }
            assertColumn(connection, "lifecycle_activation_targets",
                    "codex_home_key", true, 256);
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
            assertColumn(connection, "lifecycle_effect_outbox",
                    "binding_digest_version", true, 32);
            assertColumn(connection, "lifecycle_effect_outbox",
                    "instance_epoch", true, 128);
            assertColumn(connection, "task_terminal_tombstones",
                    "client_request_id", true, 96);
            assertColumn(connection,
                    "lifecycle_writer_exclusivity_proofs",
                    "quarantine_cursor", true, 160);
            assertColumn(connection,
                    "lifecycle_writer_exclusivity_references",
                    "reference_id", false, 160);
            assertColumn(connection,
                    "lifecycle_writer_exclusivity_proofs",
                    "controller_inventory_digest", false, 128);
            assertColumn(connection, "lifecycle_activation_targets",
                    "manifest_digest", false, 128);
            assertColumn(connection, "lifecycle_writer_generations",
                    "active_slot", true, 16);
            assertColumn(connection,
                    "lifecycle_writer_instance_registrations",
                    "expires_at", true, 0);
            assertThat(indexExists(connection,
                    "lifecycle_writer_generations",
                    "uk_lwg_active_slot", false)).isTrue();
            verifyActivationMetadataAndDestroyedCleanup(connection);
            validateJpa(mysql);
            verifyExactMySqlQuarantinePrecedence(mysql);

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
            assertRollbackBlocked(mysql, "activation_target", """
                    insert into lifecycle_activation_targets(
                      target_id,run_id,target_class,provider_evidence_lane,
                      provider_type,tenant_id,user_id,physical_worker_id,
                      model_config_id,model,codex_home_key,prompt_sha256,
                      target_commit,candidate_patch_sha256,owner_protocol,
                      worker_version,worker_protocol,
                      required_capabilities_json,manifest_digest,
                      controller_inventory_digest,generation_id,
                      writer_instance_id,status,row_version,created_at,updated_at)
                    values('fixture-target','fixture-run',
                      'ISOLATED_LOCAL_NON_FIXTURE','REAL_CODEX_MODEL',
                      'codex-biz-worker','synthetic-tenant','synthetic-user',
                      'synthetic-worker','synthetic-model-config','fixture-model',
                      'synthetic/home','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'fixture','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                      1,'fixture-worker',1,'[]','manifest','inventory',
                      'fixture-generation','fixture-instance','READY',0,now(6),now(6))
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

    private void verifyActivationMetadataAndDestroyedCleanup(
            Connection connection) throws Exception {
        connection.createStatement().execute("""
                insert into lifecycle_writer_generations(
                  generation_id,minimum_owner_protocol,target_commit,status,
                  target_id,run_id,controller_inventory_digest,active_slot,
                  activated_at,row_version)
                values('mysql-activation-generation',1,'candidate-head','ACTIVE',
                  'mysql-activation-target','mysql-activation-run',
                  'controller-digest','ACTIVE',current_timestamp(6),0)
                """);
        assertThatThrownBy(() -> connection.createStatement().execute("""
                insert into lifecycle_writer_generations(
                  generation_id,minimum_owner_protocol,target_commit,status,
                  target_id,run_id,controller_inventory_digest,active_slot,
                  activated_at,row_version)
                values('mysql-second-generation',1,'candidate-head','ACTIVE',
                  'mysql-second-target','mysql-second-run',
                  'controller-digest','ACTIVE',current_timestamp(6),0)
                """))
                .isInstanceOf(SQLException.class);
        connection.createStatement().execute("""
                insert into lifecycle_writer_instance_registrations(
                  instance_id,generation_id,owner_protocol,target_commit,
                  target_id,run_id,controller_inventory_digest,status,
                  registered_at,last_heartbeat_at,expires_at,row_version)
                values('mysql-activation-instance','mysql-activation-generation',
                  1,'candidate-head','mysql-activation-target',
                  'mysql-activation-run','controller-digest','REGISTERED',
                  current_timestamp(6),current_timestamp(6),
                  timestampadd(second,30,current_timestamp(6)),0)
                """);
        connection.createStatement().execute("""
                insert into lifecycle_writer_exclusivity_proofs(
                  proof_id,generation_id,controller_inventory_digest,
                  holder_instance_id,proof_version,status,acquired_at,
                  last_verified_at,expires_at,row_version)
                values('mysql-activation-proof','mysql-activation-generation',
                  'controller-digest','mysql-activation-instance',1,'ACTIVE',
                  current_timestamp(6),current_timestamp(6),
                  timestampadd(second,30,current_timestamp(6)),0)
                """);
        connection.createStatement().execute("""
                insert into lifecycle_activation_targets(
                  target_id,run_id,target_class,provider_evidence_lane,
                  provider_type,tenant_id,user_id,physical_worker_id,
                  model_config_id,model,codex_home_key,prompt_sha256,
                  target_commit,candidate_patch_sha256,owner_protocol,
                  worker_version,worker_protocol,required_capabilities_json,
                  manifest_digest,controller_inventory_digest,generation_id,
                  writer_instance_id,proof_id,status,destroyed_at,row_version,
                  created_at,updated_at)
                values('mysql-activation-target','mysql-activation-run',
                  'ISOLATED_LOCAL_NON_FIXTURE','REAL_CODEX_MODEL',
                  'codex-biz-worker','synthetic-tenant','synthetic-user',
                  'synthetic-worker','synthetic-model-config','fixture-model',
                  'synthetic/home',repeat('a',64),'candidate-head',repeat('b',64),
                  1,'fixture-worker',1,'[]','manifest-digest',
                  'controller-digest','mysql-activation-generation',
                  'mysql-activation-instance','mysql-activation-proof',
                  'DESTROYED',current_timestamp(6),0,current_timestamp(6),
                  current_timestamp(6))
                """);
        assertThat(scalar(connection, """
                select count(*) from lifecycle_activation_targets
                where target_id='mysql-activation-target'
                  and status='DESTROYED'
                """)).isEqualTo("1");
        assertThat(scalar(connection, """
                select count(*) from lifecycle_writer_exclusivity_proofs
                where proof_id='mysql-activation-proof'
                  and expires_at > last_verified_at
                """)).isEqualTo("1");
        connection.createStatement().execute("""
                insert into lifecycle_writer_exclusivity_references(
                  reference_id,proof_id,aggregate_type,aggregate_id,acquired_at)
                values
                  ('mysql-activation-proof:00:WORKER',
                   'mysql-activation-proof','WORKER','synthetic-worker',
                   current_timestamp(6)),
                  ('mysql-activation-proof:01:SESSION',
                   'mysql-activation-proof','SESSION','synthetic-session',
                   current_timestamp(6)),
                  ('mysql-activation-proof:02:TASK',
                   'mysql-activation-proof','TASK','synthetic-task',
                   current_timestamp(6))
                """);
        connection.createStatement().execute("""
                insert into lifecycle_effect_outbox(
                  effect_id,aggregate_type,aggregate_id,physical_worker_id,
                  provider_type,provider_task_id,dispatch_id,operation_id,
                  ownership_mode,state_generation,instance_epoch,
                  binding_digest_version,binding_digest,effect_claim,
                  aggregate_reference_id,writer_generation_id,
                  controller_inventory_digest,effect_type,effect_class,
                  effect_state,idempotency_key,proof_id,
                  effect_authorization_proof_version,authorized_at,
                  content_free_payload_json,created_at,row_version)
                values(
                  'mysql-activation-effect','TASK','synthetic-task',
                  'synthetic-worker','codex-biz-worker','provider-task',
                  'activation-dispatch','activation-dispatch','ENFORCED',
                  'state-generation','instance-epoch','JCS_SHA256_V1',
                  'binding-digest','binding-digest',
                  'mysql-activation-proof:02:TASK',
                  'mysql-activation-generation','controller-digest',
                  'TASK_CREATE_DISPATCH','EXTERNAL_PROVIDER_ONCE','COMPLETED',
                  'mysql-activation-effect-key','mysql-activation-proof','1',
                  current_timestamp(6),'{}',current_timestamp(6),0)
                """);
        assertThat(scalar(connection, """
                select count(*)
                from lifecycle_writer_exclusivity_references r
                join lifecycle_effect_outbox o
                  on o.aggregate_reference_id=r.reference_id
                where r.proof_id='mysql-activation-proof'
                  and o.proof_id=r.proof_id
                  and o.writer_generation_id='mysql-activation-generation'
                  and o.effect_state='COMPLETED'
                """)).isEqualTo("1");
        assertThat(scalar(connection, """
                select count(*) from lifecycle_writer_exclusivity_references
                where proof_id='mysql-activation-proof' and released_at is null
                """)).isEqualTo("3");

        // Destroyed-target cleanup leaves no active command/reference/proof,
        // generation or instance while preserving the DESTROYED target tombstone.
        connection.createStatement().execute(
                "delete from lifecycle_effect_outbox "
                        + "where effect_id='mysql-activation-effect'");
        connection.createStatement().execute(
                "delete from lifecycle_writer_exclusivity_references "
                        + "where proof_id='mysql-activation-proof'");
        connection.createStatement().execute(
                "delete from lifecycle_writer_exclusivity_proofs "
                        + "where proof_id='mysql-activation-proof'");
        connection.createStatement().execute(
                "delete from lifecycle_writer_instance_registrations "
                        + "where instance_id='mysql-activation-instance'");
        connection.createStatement().execute("""
                update lifecycle_writer_generations
                set status='CLOSED',active_slot=null
                where generation_id='mysql-activation-generation'
                """);
        connection.createStatement().execute(
                "delete from lifecycle_writer_generations "
                        + "where generation_id='mysql-activation-generation'");
        System.out.println("ARCH001_ACTIVATION_MYSQL metadata=verified "
                + "proof_reference_outbox=verified "
                + "unique_active_generation=verified destroyed_cleanup=verified");
    }

    private void verifyExactMySqlQuarantinePrecedence(
            MySQLContainer<?> mysql) throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                    "spring.datasource.url=" + mysql.getJdbcUrl(),
                    "spring.datasource.username=" + mysql.getUsername(),
                    "spring.datasource.password=" + mysql.getPassword(),
                    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                    "spring.jpa.hibernate.ddl-auto=none",
                    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
                    "spring.jpa.open-in-view=false")
                    .applyTo(context);
            context.register(
                    TaskLifecycleOwnerVerticalIntegrationTest.Config.class,
                    WorkerLifecycleReconciliationCommitService.class);
            context.refresh();

            ExactMySqlFixture fixture = new ExactMySqlFixture(context);
            quarantineBeforeCheckpoint(fixture, "quarantine-first");
            checkpointBeforeQuarantine(fixture, "checkpoint-first");
            blockedThenSuccessfulCheckpoint(fixture, "blocked-success");
            ordinaryCheckpointControl(fixture, "ordinary-control");
            boundedRestartContinuation(fixture);
            fixture.clear();
        }
    }

    private void quarantineBeforeCheckpoint(
            ExactMySqlFixture fixture, String suffix) throws Exception {
        AuthorityIds ids = fixture.seed(suffix);
        fixture.proofService.quarantine(ids.proofId());

        CountDownLatch quarantineLocked = new CountDownLatch(1);
        CountDownLatch checkpointEntered = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var quarantine = executor.submit(() -> fixture.transactions
                    .executeWithoutResult(status -> {
                        WorkerLifecycleSnapshotEntity locked = fixture.workers
                                .findForUpdate(ids.workerId()).orElseThrow();
                        assertThat(locked.getConflictState()).isEqualTo(
                                LifecycleConflictState
                                        .LEGACY_WRITER_EXCLUSIVITY_LOST.name());
                        assertThat(TransactionSynchronizationManager
                                .isActualTransactionActive()).isTrue();
                        quarantineLocked.countDown();
                        await(checkpointEntered);
                    }));
            var checkpoint = executor.submit(() -> {
                await(quarantineLocked);
                checkpointEntered.countDown();
                fixture.reconciliation.commit(fixture.inventory(ids), null);
            });
            quarantine.get(10, TimeUnit.SECONDS);
            checkpoint.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        fixture.assertFailClosed(ids);
        System.out.println("ARCH001_MYSQL_ORDER quarantine-before-checkpoint "
                + "proof=QUARANTINED worker/session/task=AUTHORITY_QUARANTINED");
    }

    private void checkpointBeforeQuarantine(
            ExactMySqlFixture fixture, String suffix) throws Exception {
        AuthorityIds ids = fixture.seed(suffix);
        CountDownLatch checkpointLocked = new CountDownLatch(1);
        CountDownLatch quarantineEntered = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var checkpoint = executor.submit(() -> fixture.transactions
                    .executeWithoutResult(status -> {
                        WorkerLifecycleSnapshotEntity worker = fixture.workers
                                .findForUpdate(ids.workerId()).orElseThrow();
                        worker.setAvailability(LifecycleAvailability.READY.name());
                        worker.setConflictState(LifecycleConflictState.NONE.name());
                        fixture.workers.saveAndFlush(worker);
                        assertThat(TransactionSynchronizationManager
                                .isActualTransactionActive()).isTrue();
                        checkpointLocked.countDown();
                        await(quarantineEntered);
                    }));
            var quarantine = executor.submit(() -> {
                await(checkpointLocked);
                quarantineEntered.countDown();
                fixture.proofService.quarantine(ids.proofId());
            });
            checkpoint.get(10, TimeUnit.SECONDS);
            quarantine.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        fixture.assertFailClosed(ids);
        System.out.println("ARCH001_MYSQL_ORDER checkpoint-before-quarantine "
                + "proof=QUARANTINED worker/session/task=AUTHORITY_QUARANTINED");
    }

    private void ordinaryCheckpointControl(
            ExactMySqlFixture fixture, String suffix) {
        AuthorityIds ids = fixture.seed(suffix);
        fixture.reconciliation.commit(fixture.inventory(ids), null);
        assertThat(fixture.workers.findById(ids.workerId()).orElseThrow()
                .getAvailability()).isEqualTo(LifecycleAvailability.READY.name());
        assertThat(fixture.workers.findById(ids.workerId()).orElseThrow()
                .getConflictState()).isEqualTo(LifecycleConflictState.NONE.name());
        assertThat(fixture.proofs.findById(ids.proofId()).orElseThrow()
                .getStatus()).isEqualTo("ACTIVE");
        System.out.println("ARCH001_MYSQL_CONTROL ordinary-checkpoint=READY/NONE");
    }

    private void blockedThenSuccessfulCheckpoint(
            ExactMySqlFixture fixture, String suffix) {
        AuthorityIds ids = fixture.seed(suffix);
        fixture.proofService.quarantine(ids.proofId());

        SentinelReconcileResult blocked = fixture.sentinel().reconcile(
                ids.workerId(), SentinelTrigger.TIMER,
                fixture.port(ids, false));
        assertThat(blocked.state()).isEqualTo(
                SentinelReconcileState.WORKER_UNAVAILABLE);
        fixture.assertFailClosed(ids);

        SentinelReconcileResult recovered = fixture.sentinel().reconcile(
                ids.workerId(), SentinelTrigger.TIMER,
                fixture.port(ids, true));
        assertThat(recovered.state()).isEqualTo(
                SentinelReconcileState.READY);
        fixture.assertFailClosed(ids);
        System.out.println("ARCH001_MYSQL_SEQUENCE quarantine-blocked-success "
                + "proof=QUARANTINED references=3 "
                + "worker/session/task=AUTHORITY_QUARANTINED");
    }

    private void boundedRestartContinuation(ExactMySqlFixture fixture) {
        String proofId = "mysql-proof-bounded";
        fixture.createProof(proofId, "QUARANTINING");
        List<LifecycleWriterProofReferenceEntity> batch = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            LifecycleWriterProofReferenceEntity reference =
                    new LifecycleWriterProofReferenceEntity();
            reference.setReferenceId("mysql-bounded-%03d".formatted(index));
            reference.setProofId(proofId);
            reference.setAggregateType("TASK");
            reference.setAggregateId("missing-task-%03d".formatted(index));
            reference.setAcquiredAt(LocalDateTime.now());
            batch.add(reference);
        }
        fixture.references.saveAllAndFlush(batch);

        fixture.proofService.resumeQuarantines();
        assertThat(fixture.proofs.findById(proofId).orElseThrow()
                .getQuarantineCursor()).isEqualTo("mysql-bounded-049");
        assertThat(fixture.proofs.findById(proofId).orElseThrow()
                .getStatus()).isEqualTo("QUARANTINING");
        fixture.proofService.resumeQuarantines();
        assertThat(fixture.proofs.findById(proofId).orElseThrow()
                .getQuarantineCursor()).isEqualTo("mysql-bounded-099");
        assertThat(fixture.proofs.findById(proofId).orElseThrow()
                .getStatus()).isEqualTo("QUARANTINING");
        fixture.proofService.resumeQuarantines();
        assertThat(fixture.proofs.findById(proofId).orElseThrow()
                .getQuarantineCursor()).isEqualTo("mysql-bounded-119");
        assertThat(fixture.proofs.findById(proofId).orElseThrow()
                .getStatus()).isEqualTo("QUARANTINED");
        System.out.println("ARCH001_MYSQL_BATCHES cursors=049,099,119 sizes=50,50,20");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("MySQL transaction latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private record AuthorityIds(
            String proofId, String workerId, String sessionId, String taskId) {}

    private static final class ExactMySqlFixture {
        private final LifecycleWriterProofRepository proofs;
        private final LifecycleWriterProofReferenceRepository references;
        private final WorkerLifecycleSnapshotRepository workers;
        private final SessionLifecycleSnapshotRepository sessions;
        private final TaskLifecycleSnapshotRepository tasks;
        private final WriterExclusivityProofService proofService;
        private final WorkerLifecycleReconciliationCommitService reconciliation;
        private final TransactionTemplate transactions;

        private ExactMySqlFixture(AnnotationConfigApplicationContext context) {
            proofs = context.getBean(LifecycleWriterProofRepository.class);
            references = context.getBean(
                    LifecycleWriterProofReferenceRepository.class);
            workers = context.getBean(WorkerLifecycleSnapshotRepository.class);
            sessions = context.getBean(SessionLifecycleSnapshotRepository.class);
            tasks = context.getBean(TaskLifecycleSnapshotRepository.class);
            proofService = context.getBean(WriterExclusivityProofService.class);
            reconciliation = context.getBean(
                    WorkerLifecycleReconciliationCommitService.class);
            transactions = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
        }

        private AuthorityIds seed(String suffix) {
            String proofId = "mysql-proof-" + suffix;
            String workerId = "mysql-worker-" + suffix;
            String sessionId = "mysql-session-" + suffix;
            String taskId = "mysql-task-" + suffix;
            createProof(proofId, "ACTIVE");

            WorkerLifecycleSnapshotEntity worker =
                    new WorkerLifecycleSnapshotEntity();
            worker.setPhysicalWorkerId(workerId);
            worker.setOwnershipMode("ENFORCED");
            worker.setStateGeneration("mysql-state-" + suffix);
            worker.setInstanceEpoch("mysql-epoch-" + suffix);
            worker.setAvailability(LifecycleAvailability.READY.name());
            worker.setConflictState(LifecycleConflictState.NONE.name());
            worker.setFactCursor(0);
            worker.setPolicyVersion("ARCH-001-MVP-A");
            worker.setWriterGenerationId("mysql-generation");
            worker.setSnapshotJson("{}");
            workers.saveAndFlush(worker);

            SessionLifecycleSnapshotEntity session =
                    new SessionLifecycleSnapshotEntity();
            session.setSessionId(sessionId);
            session.setPhysicalWorkerId(workerId);
            session.setOwnershipMode("ENFORCED");
            session.setCanonicalPhase("OPEN");
            session.setForegroundTaskId(taskId);
            session.setForegroundLaneState("OCCUPIED");
            session.setAvailability(LifecycleAvailability.READY.name());
            session.setConflictState(LifecycleConflictState.NONE.name());
            session.setWriterGenerationId("mysql-generation");
            sessions.saveAndFlush(session);

            TaskLifecycleSnapshotEntity task = new TaskLifecycleSnapshotEntity();
            task.setTaskId(taskId);
            task.setSessionId(sessionId);
            task.setPhysicalWorkerId(workerId);
            task.setStateGeneration("mysql-state-" + suffix);
            task.setInstanceEpoch("mysql-epoch-" + suffix);
            task.setProviderTaskId("mysql-provider-" + suffix);
            task.setOwnershipMode("ENFORCED");
            task.setCanonicalPhase("OPEN");
            task.setAvailability(LifecycleAvailability.READY.name());
            task.setConflictState(LifecycleConflictState.NONE.name());
            task.setCleanupState("NOT_REQUIRED");
            task.setFactCursor(0L);
            task.setPolicyVersion("ARCH-001-MVP-A");
            task.setWriterGenerationId("mysql-generation");
            task.setSnapshotJson("{}");
            tasks.saveAndFlush(task);

            references.saveAllAndFlush(List.of(
                    reference(proofId + ":00:WORKER", proofId,
                            "WORKER", workerId),
                    reference(proofId + ":01:SESSION", proofId,
                            "SESSION", sessionId),
                    reference(proofId + ":02:TASK", proofId,
                            "TASK", taskId)));
            return new AuthorityIds(proofId, workerId, sessionId, taskId);
        }

        private void createProof(String proofId, String status) {
            LifecycleWriterProofEntity proof = new LifecycleWriterProofEntity();
            proof.setProofId(proofId);
            proof.setGenerationId("mysql-generation");
            proof.setControllerInventoryDigest("mysql-inventory");
            proof.setHolderInstanceId("mysql-fixture-holder");
            proof.setProofVersion(1);
            proof.setStatus(status);
            proof.setAcquiredAt(LocalDateTime.now().minusMinutes(1));
            proof.setLastVerifiedAt(LocalDateTime.now());
            proof.setExpiresAt(LocalDateTime.now().plusMinutes(5));
            proofs.saveAndFlush(proof);
        }

        private LifecycleWriterProofReferenceEntity reference(
                String id, String proofId, String type, String aggregateId) {
            LifecycleWriterProofReferenceEntity reference =
                    new LifecycleWriterProofReferenceEntity();
            reference.setReferenceId(id);
            reference.setProofId(proofId);
            reference.setAggregateType(type);
            reference.setAggregateId(aggregateId);
            reference.setAcquiredAt(LocalDateTime.now());
            return reference;
        }

        private WorkerLifecycleSnapshot inventory(AuthorityIds ids) {
            return new WorkerLifecycleSnapshot(
                    new WorkerLifecycleIdentity(
                            ids.workerId(),
                            "mysql-state-" + ids.workerId()
                                    .substring("mysql-worker-".length()),
                            "mysql-epoch-" + ids.workerId()
                                    .substring("mysql-worker-".length())),
                    0, 1, true, List.of(), List.of());
        }

        private WorkerLifecycleSentinelService sentinel() {
            SentinelLeaseStore leases = (worker, holder, now, duration) ->
                    Optional.of(new SentinelLease(worker, holder, 1));
            return new WorkerLifecycleSentinelService(
                    leases, workers, reconciliation);
        }

        private WorkerLifecyclePort port(AuthorityIds ids, boolean ready) {
            WorkerLifecycleSnapshot inventory = inventory(ids);
            return new WorkerLifecyclePort() {
                @Override
                public WorkerLifecycleReadiness probe(String workerId) {
                    return new WorkerLifecycleReadiness(
                            ready, inventory.identity(),
                            ready ? Set.of("INVENTORY_V1") : Set.of(),
                            ready ? List.of()
                                    : List.of("LIFECYCLE_WORKER_UNAVAILABLE"));
                }

                @Override
                public WorkerLifecycleSnapshot inventory(
                        WorkerLifecycleIdentity expectedIdentity,
                        long afterSequence) {
                    return inventory;
                }

                @Override
                public WorkerLifecycleSnapshot events(
                        WorkerLifecycleIdentity expectedIdentity,
                        long afterSequence) {
                    return new WorkerLifecycleSnapshot(
                            inventory.identity(), 0, 1, true, List.of(),
                            List.<NormalizedLifecycleFact>of());
                }

                @Override
                public long acknowledge(
                        WorkerLifecycleIdentity expectedIdentity,
                        long throughSequence) {
                    return throughSequence;
                }
            };
        }

        private void assertFailClosed(AuthorityIds ids) {
            LifecycleWriterProofEntity proof = proofs.findById(ids.proofId())
                    .orElseThrow();
            assertThat(proof.getStatus()).isEqualTo("QUARANTINED");
            assertThat(proof.getQuarantineCursor())
                    .isEqualTo(ids.proofId() + ":02:TASK");
            assertThat(references.countByProofIdAndReleasedAtIsNull(
                    ids.proofId())).isEqualTo(3);
            assertAuthority(workers.findById(ids.workerId()).orElseThrow()
                    .getAvailability(), workers.findById(ids.workerId())
                    .orElseThrow().getConflictState());
            assertAuthority(sessions.findById(ids.sessionId()).orElseThrow()
                    .getAvailability(), sessions.findById(ids.sessionId())
                    .orElseThrow().getConflictState());
            assertAuthority(tasks.findById(ids.taskId()).orElseThrow()
                    .getAvailability(), tasks.findById(ids.taskId())
                    .orElseThrow().getConflictState());
        }

        private void assertAuthority(String availability, String conflict) {
            assertThat(availability).isEqualTo(
                    LifecycleAvailability.AUTHORITY_QUARANTINED.name());
            assertThat(conflict).isEqualTo(LifecycleConflictState
                    .LEGACY_WRITER_EXCLUSIVITY_LOST.name());
        }

        private void clear() {
            references.deleteAll();
            tasks.deleteAll();
            sessions.deleteAll();
            workers.deleteAll();
            proofs.deleteAll();
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
            execute(connection, ACTIVATION_READINESS);
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
                    .addAnnotatedClass(LifecycleActivationTargetEntity.class)
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
