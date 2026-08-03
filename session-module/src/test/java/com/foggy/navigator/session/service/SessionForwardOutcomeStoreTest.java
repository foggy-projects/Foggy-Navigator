package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionRelationEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(SessionForwardOutcomeStoreTest.Config.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:forward_outcome_store;MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class SessionForwardOutcomeStoreTest {

    private static final String REQUEST_ID = "7eb9dafe-b21e-42c6-bd45-19cd39991f24";

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EntityScan(basePackageClasses = SessionEntity.class)
    @Import({
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            SessionForwardOutcomeStore.class
    })
    static class Config {
    }

    @Autowired
    private SessionForwardOutcomeStore store;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDisposableFixture() {
        transaction().executeWithoutResult(status -> {
            entityManager.createQuery("delete from SessionRelationEntity").executeUpdate();
            entityManager.createQuery("delete from SessionEntity").executeUpdate();
        });
    }

    @Test
    void freshInsertPersistsExactExistingShapeAndReplayIsReadOnly() {
        SessionForwardOutcomeStore.OutcomeSpec spec = spec();
        persistTarget(spec);

        SessionForwardOutcomeStore.OutcomeSnapshot inserted = store.insertFresh(spec);
        SessionRelationEntity beforeReplay = relation(inserted.relationId());
        LocalDateTime originalCreatedAt = beforeReplay.getCreatedAt();
        LocalDateTime originalUpdatedAt = beforeReplay.getUpdatedAt();
        SessionForwardOutcomeStore.OutcomeSnapshot replay = store.requireExactReplay(spec);
        SessionRelationEntity afterReplay = relation(inserted.relationId());

        assertThat(inserted.relationId()).isPositive();
        assertThat(inserted.spec()).isEqualTo(spec);
        assertThat(inserted.createdAt()).isEqualTo(originalCreatedAt);
        assertThat(replay).isEqualTo(inserted);
        assertThat(afterReplay.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(afterReplay.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(countRelations()).isOne();
        assertThat(afterReplay.getRelationType()).isEqualTo("FORWARD");
        assertThat(afterReplay.getTargetMode()).isEqualTo("NEW_SESSION");
        assertThat(afterReplay.getSourceMessageId()).isEqualTo("source-reference-1");
        assertThat(afterReplay.getMetadataJson())
                .isEqualTo("{\"targetMode\":\"NEW_SESSION\",\"promptPreview\":\"Forward prompt\",\"promptLength\":14}");
    }

    @Test
    void independentFreshCommitSurvivesCallerRollback() {
        SessionForwardOutcomeStore.OutcomeSpec spec = spec();
        persistTarget(spec);

        Long relationId = transaction().execute(status -> {
            Long insertedId = store.insertFresh(spec).relationId();
            status.setRollbackOnly();
            return insertedId;
        });

        assertThat(relationId).isNotNull();
        assertThat(store.requireExactReplay(spec).relationId()).isEqualTo(relationId);
        assertThat(countRelations()).isOne();
    }

    @Test
    void freshRequiresExactTargetAndRejectsExistingOutcomeWithoutMutation() {
        SessionForwardOutcomeStore.OutcomeSpec spec = spec();

        assertThatThrownBy(() -> store.insertFresh(spec)).isInstanceOf(
                SessionForwardOutcomeStore.ForwardOutcomeConflictException.class);

        persistTarget(spec);
        SessionForwardOutcomeStore.OutcomeSnapshot first = store.insertFresh(spec);
        assertThatThrownBy(() -> store.insertFresh(spec)).isInstanceOf(
                SessionForwardOutcomeStore.ForwardOutcomeConflictException.class);
        assertThat(countRelations()).isOne();
        assertThat(relation(first.relationId()).getUserId()).isEqualTo("owner-1");

        resetDisposableFixture();
        persistTarget(spec, target -> target.setUserId("other-owner"));
        assertThatThrownBy(() -> store.insertFresh(spec)).isInstanceOf(
                SessionForwardOutcomeStore.ForwardOutcomeConflictException.class);
        assertThat(countRelations()).isZero();
    }

    @Test
    void concurrentFreshCallersProduceOneRowAndConflictsForTheRest() throws Exception {
        SessionForwardOutcomeStore.OutcomeSpec spec = spec();
        persistTarget(spec);
        int callers = 6;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<Object>> calls = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(ignored -> (Callable<Object>) () -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        try {
                            return store.insertFresh(spec);
                        } catch (RuntimeException failure) {
                            return failure;
                        }
                    }).toList();
            List<Future<Object>> futures = calls.stream().map(pool::submit).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(15, TimeUnit.SECONDS));
            }

            assertThat(outcomes.stream()
                    .filter(SessionForwardOutcomeStore.OutcomeSnapshot.class::isInstance))
                    .hasSize(1);
            assertThat(outcomes.stream()
                    .filter(SessionForwardOutcomeStore.ForwardOutcomeConflictException.class::isInstance))
                    .hasSize(callers - 1);
            assertThat(countRelations()).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void replayRequiresExactlyOneRowAndEveryStoredFieldToMatch() {
        SessionForwardOutcomeStore.OutcomeSpec baseline = spec();
        persistTarget(baseline);
        store.insertFresh(baseline);

        List<SessionForwardOutcomeStore.OutcomeSpec> drift = List.of(
                spec(f -> f.ownerUserId = "owner-2"),
                spec(f -> f.tenantId = "tenant-2"),
                spec(f -> f.sourceSessionId = "source-session-2"),
                spec(f -> f.sourceReferenceId = "source-reference-2"),
                spec(f -> f.sourceWorkerId = "source-worker-2"),
                spec(f -> f.sourceDirectoryId = "source-directory-2"),
                spec(f -> f.sourceMilestoneId = "source-milestone-2"),
                spec(f -> f.targetWorkerId = "target-worker-2"),
                spec(f -> f.targetDirectoryId = "target-directory-2"),
                spec(f -> f.targetMilestoneId = "target-milestone-2"),
                spec(f -> f.targetProviderType = "claude-worker"),
                spec(f -> f.targetModelConfigId = "model-config-2"),
                spec(f -> f.metadataJson = "{\"changed\":true}"));
        drift.forEach(changed -> assertThatThrownBy(() -> store.requireExactReplay(changed))
                .isInstanceOf(SessionForwardOutcomeStore.ForwardOutcomeConflictException.class));
        assertThat(countRelations()).isOne();

        SessionForwardOutcomeStore.OutcomeSpec missing = spec(f ->
                f.targetSessionId = "fwd_" + "f".repeat(60));
        assertThatThrownBy(() -> store.requireExactReplay(missing)).isInstanceOf(
                SessionForwardOutcomeStore.ForwardOutcomeConflictException.class);

        transaction().executeWithoutResult(status -> entityManager.persist(newRelation(baseline)));
        assertThat(countRelations()).isEqualTo(2);
        assertThatThrownBy(() -> store.requireExactReplay(baseline)).isInstanceOf(
                SessionForwardOutcomeStore.ForwardOutcomeConflictException.class);
    }

    @Test
    void planAndTaskProjectionIsExactAndRedacted() {
        SessionForwardNewSessionPlan plan = plan();
        String targetSessionId = SessionForwardTargetSessionReservationService.deriveSessionId(
                REQUEST_ID, plan.ownerUserId(), plan.tenantId());
        DispatchTaskDTO task = task(targetSessionId, "target-directory-1", "codex-worker");

        SessionForwardOutcomeStore.OutcomeSpec projected =
                SessionForwardOutcomeStore.OutcomeSpec.from(plan, targetSessionId, task);
        assertThat(projected).isEqualTo(spec());
        assertThat(projected.toString()).doesNotContain("Forward prompt");
        assertThat(new SessionForwardOutcomeStore.OutcomeSnapshot(
                1L, projected, LocalDateTime.of(2026, 8, 4, 1, 2)).toString())
                .doesNotContain("Forward prompt");

        assertThatThrownBy(() -> SessionForwardOutcomeStore.OutcomeSpec.from(
                plan,
                targetSessionId,
                task(targetSessionId, "other-directory", "codex-worker")))
                .isInstanceOf(IllegalArgumentException.class);
        SessionForwardOutcomeStore.OutcomeSpec providerUnknown =
                SessionForwardOutcomeStore.OutcomeSpec.from(
                plan,
                targetSessionId,
                task(targetSessionId, "target-directory-1", null));
        assertThat(providerUnknown.targetProviderType()).isNull();
    }

    @Test
    void nullableProviderPersistsAndReplaysAsExactUnknown() {
        SessionForwardOutcomeStore.OutcomeSpec providerUnknown =
                spec(f -> f.targetProviderType = null);
        persistTarget(providerUnknown);

        SessionForwardOutcomeStore.OutcomeSnapshot inserted =
                store.insertFresh(providerUnknown);
        SessionForwardOutcomeStore.OutcomeSnapshot replay =
                store.requireExactReplay(providerUnknown);

        assertThat(relation(inserted.relationId()).getTargetProviderType()).isNull();
        assertThat(replay).isEqualTo(inserted);
        assertThatThrownBy(() -> store.requireExactReplay(spec()))
                .isInstanceOf(SessionForwardOutcomeStore.ForwardOutcomeConflictException.class);
        assertThat(countRelations()).isOne();
    }

    @Test
    void storeSurfaceHasNoRepositoryUpdateDeleteRepairOrBroadScan() {
        assertThat(Arrays.stream(SessionForwardOutcomeStore.class.getDeclaredMethods())
                .filter(method -> !Modifier.isPrivate(method.getModifiers()))
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .map(Method::getName))
                .containsExactlyInAnyOrder("insertFresh", "requireExactReplay");
        assertThat(Arrays.stream(SessionForwardOutcomeStore.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .map(Class::getSimpleName))
                .containsExactly("PlatformTransactionManager");
        assertThat(Arrays.stream(SessionForwardOutcomeStore.class.getDeclaredMethods())
                .map(Method::getName))
                .noneMatch(name -> name.matches("(?i).*(update|delete|repair|reconcile|scan).*"));
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private long countRelations() {
        return transaction().execute(status -> entityManager.createQuery(
                        "select count(relation) from SessionRelationEntity relation", Long.class)
                .getSingleResult());
    }

    private SessionRelationEntity relation(Long id) {
        return transaction().execute(status -> entityManager.find(SessionRelationEntity.class, id));
    }

    private void persistTarget(SessionForwardOutcomeStore.OutcomeSpec spec) {
        persistTarget(spec, ignored -> { });
    }

    private void persistTarget(
            SessionForwardOutcomeStore.OutcomeSpec spec,
            Consumer<SessionEntity> mutation) {
        transaction().executeWithoutResult(status -> {
            SessionEntity target = new SessionEntity();
            target.setId(spec.targetSessionId());
            target.setUserId(spec.ownerUserId());
            target.setTenantId(spec.tenantId());
            target.setStatus("ACTIVE");
            target.setInteractionState("PROCESSING");
            target.setCurrentDirectoryId(spec.targetDirectoryId());
            target.setMilestoneId(spec.targetMilestoneId());
            mutation.accept(target);
            entityManager.persist(target);
        });
    }

    private SessionRelationEntity newRelation(SessionForwardOutcomeStore.OutcomeSpec spec) {
        SessionRelationEntity relation = new SessionRelationEntity();
        relation.setUserId(spec.ownerUserId());
        relation.setTenantId(spec.tenantId());
        relation.setRelationType("FORWARD");
        relation.setTargetMode("NEW_SESSION");
        relation.setSourceSessionId(spec.sourceSessionId());
        relation.setSourceMessageId(spec.sourceReferenceId());
        relation.setTargetSessionId(spec.targetSessionId());
        relation.setSourceWorkerId(spec.sourceWorkerId());
        relation.setSourceDirectoryId(spec.sourceDirectoryId());
        relation.setSourceMilestoneId(spec.sourceMilestoneId());
        relation.setTargetWorkerId(spec.targetWorkerId());
        relation.setTargetDirectoryId(spec.targetDirectoryId());
        relation.setTargetMilestoneId(spec.targetMilestoneId());
        relation.setTargetProviderType(spec.targetProviderType());
        relation.setTargetModelConfigId(spec.targetModelConfigId());
        relation.setMetadataJson(spec.metadataJson());
        return relation;
    }

    private SessionForwardOutcomeStore.OutcomeSpec spec() {
        return spec(ignored -> { });
    }

    private SessionForwardOutcomeStore.OutcomeSpec spec(Consumer<SpecFixture> mutation) {
        SpecFixture fixture = new SpecFixture();
        mutation.accept(fixture);
        return fixture.build();
    }

    private SessionForwardNewSessionPlan plan() {
        return new SessionForwardNewSessionPlan(
                "owner-1",
                "tenant-1",
                new SessionForwardNewSessionPlan.SourceSnapshot(
                        "source-session-1",
                        SessionForwardNewSessionPlan.SourceKind.MESSAGE,
                        "source-reference-1",
                        "source-task-1",
                        "Source content",
                        "source-worker-1",
                        "source-directory-1",
                        "source-milestone-1"),
                "source-session-1",
                "Forward prompt",
                new SessionForwardNewSessionPlan.TargetExecution(
                        "target-worker-1",
                        "target-directory-1",
                        "/workspace/project",
                        "agent-1",
                        "target-milestone-1",
                        "gpt-5.6",
                        "model-config-1",
                        "workspace-write",
                        7,
                        null,
                        null,
                        null));
    }

    private DispatchTaskDTO task(
            String targetSessionId,
            String directoryId,
            String providerType) {
        return DispatchTaskDTO.builder()
                .taskId("task-1")
                .sessionId(targetSessionId)
                .workerId("target-worker-1")
                .directoryId(directoryId)
                .agentId("agent-1")
                .providerType(providerType)
                .model("gpt-5.6")
                .modelConfigId("model-config-1")
                .build();
    }

    private static final class SpecFixture {
        private String ownerUserId = "owner-1";
        private String tenantId = "tenant-1";
        private String sourceSessionId = "source-session-1";
        private String sourceReferenceId = "source-reference-1";
        private String sourceWorkerId = "source-worker-1";
        private String sourceDirectoryId = "source-directory-1";
        private String sourceMilestoneId = "source-milestone-1";
        private String targetSessionId =
                SessionForwardTargetSessionReservationService.deriveSessionId(
                        REQUEST_ID, ownerUserId, tenantId);
        private String targetWorkerId = "target-worker-1";
        private String targetDirectoryId = "target-directory-1";
        private String targetMilestoneId = "target-milestone-1";
        private String targetProviderType = "codex-worker";
        private String targetModelConfigId = "model-config-1";
        private String metadataJson =
                "{\"targetMode\":\"NEW_SESSION\",\"promptPreview\":\"Forward prompt\",\"promptLength\":14}";

        private SessionForwardOutcomeStore.OutcomeSpec build() {
            return new SessionForwardOutcomeStore.OutcomeSpec(
                    ownerUserId,
                    tenantId,
                    sourceSessionId,
                    sourceReferenceId,
                    sourceWorkerId,
                    sourceDirectoryId,
                    sourceMilestoneId,
                    targetSessionId,
                    targetWorkerId,
                    targetDirectoryId,
                    targetMilestoneId,
                    targetProviderType,
                    targetModelConfigId,
                    metadataJson);
        }
    }
}
