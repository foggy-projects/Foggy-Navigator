package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.command.CommandReceiptTransactionFence;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenApiManagementTerminationDomainFenceTest {

    @Mock
    private SessionTaskRepository canonicalTasks;
    @Mock
    private SessionRepository sessions;
    @Mock
    private TaskLifecycleSnapshotRepository lifecycleTasks;
    @Mock
    private EntityManager entityManager;

    private OpenApiManagementTerminationDomainFence fence;

    @BeforeEach
    void setUp() {
        fence = new OpenApiManagementTerminationDomainFence(
                canonicalTasks, sessions, lifecycleTasks, entityManager);
    }

    @Test
    void unenrolledAndProductionShapeShadowAreAllowedAfterCanonicalTaskLock() {
        CanonicalCommandEnvelope.CommandBinding binding = managementBinding();
        SessionTaskEntity task = canonicalTask();
        SessionEntity session = canonicalSession();
        when(canonicalTasks.findByTaskIdForUpdate("task-1"))
                .thenReturn(Optional.of(task));
        when(sessions.findByIdAndUserIdAndTenantId(
                "session-1", "durable-owner", "tenant-1"))
                .thenReturn(Optional.of(session));
        when(lifecycleTasks.findForUpdate("task-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(lifecycle("SHADOW", null)));
        when(lifecycleTasks.findById("task-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(lifecycle("SHADOW", null)));

        CommandReceiptTransactionFence.LockedDomain unenrolled = fence.lock(binding);
        CommandReceiptTransactionFence.LockedDomain shadow = fence.lock(binding);

        assertTrue(unenrolled.eligible());
        assertTrue(shadow.eligible());
        assertEquals("LockedDomain[content-free]", shadow.toString());
        InOrder order = inOrder(canonicalTasks, sessions, lifecycleTasks);
        order.verify(lifecycleTasks).findById("task-1");
        order.verify(canonicalTasks).findByTaskIdForUpdate("task-1");
        order.verify(sessions).findByIdAndUserIdAndTenantId(
                "session-1", "durable-owner", "tenant-1");
        order.verify(lifecycleTasks).findForUpdate("task-1");
    }

    @Test
    void nonNullShadowSessionDriftIsRejected() {
        TaskLifecycleSnapshotEntity shadow =
                lifecycle("SHADOW", "session-drift");
        when(lifecycleTasks.findById("task-1"))
                .thenReturn(Optional.of(shadow));
        when(canonicalTasks.findByTaskIdForUpdate("task-1"))
                .thenReturn(Optional.of(canonicalTask()));
        when(sessions.findByIdAndUserIdAndTenantId(
                "session-1", "durable-owner", "tenant-1"))
                .thenReturn(Optional.of(canonicalSession()));
        when(lifecycleTasks.findForUpdate("task-1"))
                .thenReturn(Optional.of(shadow));

        CommandReceiptTransactionFence.LockedDomain domain =
                fence.lock(managementBinding());

        assertFalse(domain.eligible());
        assertEquals(OpenApiManagementTerminationDomainFence.RESOURCE_CONFLICT,
                assertThrows(IllegalStateException.class, domain::requireEligible)
                        .getMessage());
    }

    @Test
    void enforcedNullAndUnknownOwnershipModesFailClosed() {
        when(lifecycleTasks.findById("task-1"))
                .thenReturn(Optional.of(lifecycle("ENFORCED", "session-1")))
                .thenReturn(Optional.of(lifecycle(null, "session-1")))
                .thenReturn(Optional.of(lifecycle("LEGACY", "session-1")));

        for (int index = 0; index < 3; index++) {
            CommandReceiptTransactionFence.LockedDomain domain =
                    fence.lock(managementBinding());
            assertFalse(domain.eligible());
            IllegalStateException rejected = assertThrows(
                    IllegalStateException.class, domain::requireEligible);
            assertEquals(
                    OpenApiManagementTerminationDomainFence
                            .DOMAIN_NOT_NON_ENFORCED,
                    rejected.getMessage());
        }
        verifyNoInteractions(canonicalTasks, sessions);
    }

    @Test
    void shadowObservedBeforeTaskLockIsRecheckedUnderLock() {
        TaskLifecycleSnapshotEntity observed = lifecycle("SHADOW", null);
        when(lifecycleTasks.findById("task-1"))
                .thenReturn(Optional.of(observed));
        when(canonicalTasks.findByTaskIdForUpdate("task-1"))
                .thenReturn(Optional.of(canonicalTask()));
        when(sessions.findByIdAndUserIdAndTenantId(
                "session-1", "durable-owner", "tenant-1"))
                .thenReturn(Optional.of(canonicalSession()));
        when(lifecycleTasks.findForUpdate("task-1"))
                .thenReturn(Optional.of(lifecycle("ENFORCED", "session-1")));

        CommandReceiptTransactionFence.LockedDomain domain =
                fence.lock(managementBinding());

        assertFalse(domain.eligible());
        assertEquals(OpenApiManagementTerminationDomainFence
                        .DOMAIN_NOT_NON_ENFORCED,
                assertThrows(IllegalStateException.class, domain::requireEligible)
                        .getMessage());
    }

    @Test
    void durableIdentityDriftRejectsBeforeLifecycleLookup() {
        SessionTaskEntity task = canonicalTask();
        task.setUserId("owner-drift");
        when(lifecycleTasks.findById("task-1"))
                .thenReturn(Optional.empty());
        when(canonicalTasks.findByTaskIdForUpdate("task-1"))
                .thenReturn(Optional.of(task));

        CommandReceiptTransactionFence.LockedDomain domain =
                fence.lock(managementBinding());

        assertFalse(domain.eligible());
        IllegalStateException rejected = assertThrows(
                IllegalStateException.class, domain::requireEligible);
        assertEquals(OpenApiManagementTerminationDomainFence.RESOURCE_CONFLICT,
                rejected.getMessage());
        verifyNoInteractions(sessions);
    }

    @Test
    void sameRouteClientAppActorIsClaimedAndRejectedWithoutResourceLookup() {
        CanonicalCommandEnvelope.CommandBinding management = managementBinding();
        CanonicalCommandEnvelope.CommandBinding clientApp =
                new CanonicalCommandEnvelope.CommandBinding(
                        management.commandKind(),
                        management.ingress(),
                        management.request(),
                        new CanonicalCommandEnvelope.Actor(
                                CanonicalCommandEnvelope.ActorKind
                                        .AUTHENTICATED_PRINCIPAL,
                                AuthorizationPrincipalType.CLIENT_APP,
                                AuthorizationCredentialLane.CLIENT_APP_RUNTIME_ACCESS,
                                "client-app-fingerprint",
                                null),
                        new CanonicalCommandEnvelope.Ownership(
                                management.ownership().tenantReference(),
                                management.ownership().ownerReference(),
                                "client-app-1",
                                "upstream-1"),
                        management.target(),
                        management.effect());

        assertTrue(fence.claims(clientApp));
        CommandReceiptTransactionFence.LockedDomain domain = fence.lock(clientApp);

        assertFalse(domain.eligible());
        assertEquals(OpenApiManagementTerminationDomainFence.AUTHORITY_CONFLICT,
                assertThrows(IllegalStateException.class, domain::requireEligible)
                        .getMessage());
        verifyNoInteractions(canonicalTasks, sessions, lifecycleTasks);
    }

    static CanonicalCommandEnvelope.CommandBinding managementBinding() {
        return new CanonicalCommandEnvelope.CommandBinding(
                CanonicalCommandEnvelope.CommandKind.TERMINATE,
                new CanonicalCommandEnvelope.Ingress(
                        CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                        CommandReceiptTransactionFence.OPEN_API_CLIENT_SURFACE,
                        CommandReceiptTransactionFence.OPEN_API_AGENT_TASK_CANCEL_ROUTE),
                new CanonicalCommandEnvelope.Request(
                        "550e8400-e29b-41d4-a716-446655440000",
                        "550e8400-e29b-41d4-a716-446655440000",
                        "550e8400-e29b-41d4-a716-446655440000"),
                new CanonicalCommandEnvelope.Actor(
                        CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                        AuthorizationPrincipalType.NAVIGATOR_USER,
                        AuthorizationCredentialLane.NAVIGATOR_JWT,
                        "navigator-user-fingerprint",
                        null),
                new CanonicalCommandEnvelope.Ownership(
                        TaskTerminationCommandCoordinator.canonicalTenantReference(
                                "tenant-1"),
                        "durable-owner",
                        null,
                        null),
                new CanonicalCommandEnvelope.Target(
                        CanonicalCommandEnvelope.TargetKind.TASK,
                        "task-1",
                        "agent-1",
                        "codex-worker",
                        "worker-1",
                        "model-config-1",
                        "task-1",
                        "session-1"),
                new CanonicalCommandEnvelope.Effect(
                        CommandReceiptTransactionFence.TASK_TERMINATE_ACTION,
                        "TASK_TERMINATE_SCOPE_LP_UTF8_SHA256_V1:digest"));
    }

    static SessionTaskEntity canonicalTask() {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId("task-1");
        task.setSessionId("session-1");
        task.setUserId("durable-owner");
        task.setTenantId("tenant-1");
        task.setAgentId("agent-1");
        task.setProviderType("codex-worker");
        task.setWorkerId("worker-1");
        task.setModelConfigId("model-config-1");
        task.setStatus("RUNNING");
        return task;
    }

    static SessionEntity canonicalSession() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("durable-owner");
        session.setTenantId("tenant-1");
        session.setStatus("ACTIVE");
        return session;
    }

    private static TaskLifecycleSnapshotEntity lifecycle(
            String ownershipMode, String sessionId) {
        TaskLifecycleSnapshotEntity lifecycle = new TaskLifecycleSnapshotEntity();
        lifecycle.setTaskId("task-1");
        lifecycle.setSessionId(sessionId);
        lifecycle.setOwnershipMode(ownershipMode);
        return lifecycle;
    }
}

@SpringJUnitConfig(OpenApiManagementTerminationDomainFenceConcurrencyTest.Config.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:management_termination_fence;"
                + "MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class OpenApiManagementTerminationDomainFenceConcurrencyTest {

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EntityScan(basePackageClasses = {
            SessionTaskEntity.class,
            TaskLifecycleSnapshotEntity.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            SessionTaskRepository.class,
            SessionRepository.class,
            TaskLifecycleSnapshotRepository.class
    })
    @Import({
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    static class Config {

        @Bean
        OpenApiManagementTerminationDomainFence managementTerminationDomainFence(
                SessionTaskRepository tasks,
                SessionRepository sessions,
                TaskLifecycleSnapshotRepository lifecycleTasks,
                EntityManager entityManager) {
            return new OpenApiManagementTerminationDomainFence(
                    tasks, sessions, lifecycleTasks, entityManager);
        }
    }

    @Autowired
    private OpenApiManagementTerminationDomainFence fence;
    @Autowired
    private SessionTaskRepository tasks;
    @Autowired
    private SessionRepository sessions;
    @Autowired
    private TaskLifecycleSnapshotRepository lifecycleTasks;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDisposableFixture() {
        transaction().executeWithoutResult(status -> {
            lifecycleTasks.deleteAllInBatch();
            tasks.deleteAllInBatch();
            sessions.deleteAllInBatch();
        });
    }

    @Test
    void enforcedPreflightDoesNotJoinCanonicalTaskLockChain()
            throws Exception {
        persistDomain("ENFORCED");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch taskLocked = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        Future<?> holder = null;
        try {
            holder = executor.submit(() -> transaction().executeWithoutResult(status -> {
                tasks.findByTaskIdForUpdate("task-1").orElseThrow();
                taskLocked.countDown();
                await(releaseTask);
            }));
            assertTrue(taskLocked.await(2, TimeUnit.SECONDS));

            Future<CommandReceiptTransactionFence.LockedDomain> rejected =
                    executor.submit(() -> transaction().execute(status ->
                            fence.lock(OpenApiManagementTerminationDomainFenceTest
                                    .managementBinding())));

            CommandReceiptTransactionFence.LockedDomain domain =
                    rejected.get(2, TimeUnit.SECONDS);
            assertFalse(domain.eligible());
            assertEquals(OpenApiManagementTerminationDomainFence
                            .DOMAIN_NOT_NON_ENFORCED,
                    assertThrows(IllegalStateException.class,
                            domain::requireEligible).getMessage());
        } finally {
            releaseTask.countDown();
            if (holder != null) {
                holder.get(2, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentShadowEnrollmentWinsAndLockedRecheckRejectsManagement()
            throws Exception {
        persistDomain("SHADOW");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch enrollmentChanged = new CountDownLatch(1);
        CountDownLatch releaseEnrollment = new CountDownLatch(1);
        Future<?> enrollment = null;
        try {
            enrollment = executor.submit(() -> transaction()
                    .executeWithoutResult(status -> {
                        tasks.findByTaskIdForUpdate("task-1").orElseThrow();
                        TaskLifecycleSnapshotEntity snapshot = lifecycleTasks
                                .findForUpdate("task-1").orElseThrow();
                        snapshot.setOwnershipMode("ENFORCED");
                        lifecycleTasks.saveAndFlush(snapshot);
                        enrollmentChanged.countDown();
                        await(releaseEnrollment);
                    }));
            assertTrue(enrollmentChanged.await(2, TimeUnit.SECONDS));

            Future<CommandReceiptTransactionFence.LockedDomain> management =
                    executor.submit(() -> transaction().execute(status ->
                            fence.lock(OpenApiManagementTerminationDomainFenceTest
                                    .managementBinding())));
            assertFalse(management.isDone());
            releaseEnrollment.countDown();

            CommandReceiptTransactionFence.LockedDomain domain =
                    management.get(3, TimeUnit.SECONDS);
            assertFalse(domain.eligible());
            assertEquals(OpenApiManagementTerminationDomainFence
                            .DOMAIN_NOT_NON_ENFORCED,
                    assertThrows(IllegalStateException.class,
                            domain::requireEligible).getMessage());
        } finally {
            releaseEnrollment.countDown();
            if (enrollment != null) {
                enrollment.get(3, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
        }
    }

    private void persistDomain(String ownershipMode) {
        transaction().executeWithoutResult(status -> {
            sessions.saveAndFlush(OpenApiManagementTerminationDomainFenceTest
                    .canonicalSession());
            tasks.saveAndFlush(OpenApiManagementTerminationDomainFenceTest
                    .canonicalTask());
            TaskLifecycleSnapshotEntity lifecycle =
                    new TaskLifecycleSnapshotEntity();
            lifecycle.setTaskId("task-1");
            lifecycle.setOwnershipMode(ownershipMode);
            lifecycle.setCanonicalPhase("OPEN");
            lifecycle.setAvailability("READY");
            lifecycle.setConflictState("NONE");
            lifecycle.setCleanupState("NOT_REQUIRED");
            lifecycle.setFactCursor(0L);
            lifecycle.setPolicyVersion("ARCH-001-MVP-A");
            lifecycle.setSnapshotJson("{}");
            lifecycleTasks.saveAndFlush(lifecycle);
        });
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("CONCURRENCY_FIXTURE_TIMEOUT");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "CONCURRENCY_FIXTURE_INTERRUPTED", interrupted);
        }
    }
}
