package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.session.config.SessionModuleAutoConfiguration;
import com.foggy.navigator.session.repository.SessionForwardTargetSessionReservationRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringJUnitConfig(SessionForwardTargetSessionReservationServiceTest.Config.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:forward_session_reservation;MODE=MYSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class SessionForwardTargetSessionReservationServiceTest {

    private static final String REQUEST_ID = "9c420e52-5f30-4b05-9cf1-37a451f56145";

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EntityScan(basePackageClasses = SessionEntity.class)
    @EnableJpaRepositories(
            basePackageClasses = SessionRepository.class,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = SessionRepository.class))
    @Import({
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            SessionForwardTargetSessionReservationRepository.class,
            SessionForwardTargetSessionReservationService.class
    })
    static class Config {
    }

    @Autowired
    private SessionForwardTargetSessionReservationService service;

    @Autowired
    private SessionRepository sessions;

    @MockitoSpyBean
    private SessionForwardTargetSessionReservationRepository inserts;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDisposableFixture() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createQuery("delete from SessionEntity").executeUpdate());
    }

    @Test
    void deterministicReservationCreatesOnceAndExactReplayNeverUpdatesWinner() {
        var spec = spec();

        var created = service.reserve(REQUEST_ID, spec);
        SessionEntity first = sessions.findById(created.sessionId()).orElseThrow();
        LocalDateTime originalUpdatedAt = first.getUpdatedAt();
        var replay = service.reserve(REQUEST_ID, spec);
        SessionEntity afterReplay = sessions.findById(created.sessionId()).orElseThrow();

        assertThat(created.disposition()).isEqualTo(
                SessionForwardTargetSessionReservationService.ReservationDisposition.CREATED);
        assertThat(replay.disposition()).isEqualTo(
                SessionForwardTargetSessionReservationService.ReservationDisposition.EXACT_REPLAY);
        assertThat(replay.sessionId()).isEqualTo(created.sessionId());
        assertThat(created.sessionId()).startsWith("fwd_").hasSize(64);
        assertThat(sessions.count()).isOne();
        assertThat(afterReplay.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(afterReplay.getUserId()).isEqualTo("owner-1");
        assertThat(afterReplay.getTenantId()).isEqualTo("tenant-1");
        assertThat(afterReplay.getAgentId()).isEqualTo("agent-1");
        assertThat(afterReplay.getParentSessionId()).isEqualTo("root-session");
        assertThat(afterReplay.getTitle()).isEqualTo("Forwarded prompt");
        assertThat(afterReplay.getCurrentDirectoryId()).isEqualTo("directory-1");
        assertThat(afterReplay.getMilestoneId()).isEqualTo("milestone-1");
        assertThat(afterReplay.getLatestModel()).isEqualTo("model-1");
    }

    @Test
    void immutableBindingDriftAndDeletedWinnerFailClosedWithoutMutation() {
        var original = spec();
        String sessionId = service.reserve(REQUEST_ID, original).sessionId();

        List<SessionForwardTargetSessionReservationService.ReservationSpec> variants = List.of(
                newSpec("agent-2", "root-session", "Forwarded prompt", "directory-1",
                        "milestone-1", "model-1"),
                newSpec("agent-1", "other-root", "Forwarded prompt", "directory-1",
                        "milestone-1", "model-1"),
                newSpec("agent-1", "root-session", "Changed title", "directory-1",
                        "milestone-1", "model-1"),
                newSpec("agent-1", "root-session", "Forwarded prompt", "directory-2",
                        "milestone-1", "model-1"),
                newSpec("agent-1", "root-session", "Forwarded prompt", "directory-1",
                        "milestone-2", "model-1"),
                newSpec("agent-1", "root-session", "Forwarded prompt", "directory-1",
                        "milestone-1", "model-2"));

        for (var changed : variants) {
            assertThatThrownBy(() -> service.reserve(REQUEST_ID, changed))
                    .isInstanceOf(SessionForwardTargetSessionReservationService
                            .SessionReservationConflictException.class)
                    .hasMessage("FORWARD_SESSION_RESERVATION_CONFLICT");
        }

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            SessionEntity deleted = sessions.findById(sessionId).orElseThrow();
            deleted.setDeletedAt(LocalDateTime.of(2026, 8, 3, 1, 2));
            deleted.setStatus("DELETED");
            sessions.saveAndFlush(deleted);
        });
        assertThatThrownBy(() -> service.reserve(REQUEST_ID, original))
                .isInstanceOf(SessionForwardTargetSessionReservationService
                        .SessionReservationConflictException.class);

        SessionEntity unchanged = sessions.findById(sessionId).orElseThrow();
        assertThat(unchanged.getAgentId()).isEqualTo("agent-1");
        assertThat(unchanged.getTitle()).isEqualTo("Forwarded prompt");
        assertThat(sessions.count()).isOne();
    }

    @Test
    void preexistingOwnerOrTenantMismatchAtDerivedIdentityCannotBeMerged() {
        var requested = spec();
        String sessionId = SessionForwardTargetSessionReservationService.deriveSessionId(
                REQUEST_ID, requested.ownerUserId(), requested.tenantId());
        SessionEntity conflicting = row(sessionId, requested);
        conflicting.setUserId("other-owner");
        conflicting.setTenantId("other-tenant");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.persist(conflicting));

        assertThatThrownBy(() -> service.reserve(REQUEST_ID, requested))
                .isInstanceOf(SessionForwardTargetSessionReservationService
                        .SessionReservationConflictException.class);

        SessionEntity winner = sessions.findById(sessionId).orElseThrow();
        assertThat(winner.getUserId()).isEqualTo("other-owner");
        assertThat(winner.getTenantId()).isEqualTo("other-tenant");
        assertThat(sessions.count()).isOne();
    }

    @Test
    void concurrentExactCallersProduceOneRowAndOneCreatedDisposition() throws Exception {
        int callers = 8;
        AtomicInteger insertAttempts = barrierAtInsert(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<SessionForwardTargetSessionReservationService.ReservationResult>> calls =
                    java.util.stream.IntStream.range(0, callers)
                            .mapToObj(ignored -> (Callable<SessionForwardTargetSessionReservationService
                                    .ReservationResult>) () -> {
                                ready.countDown();
                                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                                return service.reserve(REQUEST_ID, spec());
                            })
                            .toList();
            List<Future<SessionForwardTargetSessionReservationService.ReservationResult>> futures =
                    calls.stream().map(pool::submit).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<SessionForwardTargetSessionReservationService.ReservationResult> results =
                    futures.stream().map(this::await).toList();
            assertThat(results).extracting(
                            SessionForwardTargetSessionReservationService.ReservationResult::sessionId)
                    .containsOnly(results.get(0).sessionId());
            assertThat(results).extracting(
                            SessionForwardTargetSessionReservationService.ReservationResult::disposition)
                    .containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(
                                    java.util.stream.Stream.of(
                                            SessionForwardTargetSessionReservationService
                                                    .ReservationDisposition.CREATED),
                                    java.util.stream.Stream.generate(() ->
                                                    SessionForwardTargetSessionReservationService
                                                            .ReservationDisposition.EXACT_REPLAY)
                                            .limit(callers - 1))
                            .toList());
            assertThat(insertAttempts).hasValue(callers);
            assertThat(sessions.count()).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentBindingDriftReturnsStableConflictAndNeverMergesWinner() throws Exception {
        AtomicInteger insertAttempts = barrierAtInsert(2);
        CountDownLatch start = new CountDownLatch(1);
        var first = spec();
        var second = newSpec("agent-1", "root-session", "Different title", "directory-1",
                "milestone-1", "model-1");
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> firstResult = pool.submit(() -> reserveOrFailure(start, first));
            Future<Object> secondResult = pool.submit(() -> reserveOrFailure(start, second));
            start.countDown();

            List<Object> outcomes = List.of(
                    firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS));
            List<Object> successful = outcomes.stream()
                    .filter(SessionForwardTargetSessionReservationService
                            .ReservationResult.class::isInstance)
                    .toList();
            List<Object> conflicts = outcomes.stream()
                    .filter(SessionForwardTargetSessionReservationService
                            .SessionReservationConflictException.class::isInstance)
                    .toList();
            assertThat(successful)
                    .hasSize(1);
            assertThat(conflicts)
                    .singleElement()
                    .extracting(Object::toString)
                    .asString()
                    .contains("FORWARD_SESSION_RESERVATION_CONFLICT");
            assertThat(insertAttempts).hasValue(2);
            assertThat(sessions.count()).isOne();
            SessionEntity winner = sessions.findAll().get(0);
            assertThat(winner.getTitle()).isIn(first.title(), second.title());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reservationCommitSurvivesCallerRollback() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        String sessionId = outer.execute(status -> {
            String reserved = service.reserve(REQUEST_ID, spec()).sessionId();
            status.setRollbackOnly();
            return reserved;
        });

        assertThat(sessionId).isNotNull();
        assertThat(sessions.findById(sessionId)).isPresent();
        assertThat(sessions.count()).isOne();
    }

    @Test
    void canonicalRequestAndKnownProviderBindingAreValidated() {
        assertThatThrownBy(() -> service.reserve("not-a-uuid", spec()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical UUID");

        var providerSpec = new SessionForwardTargetSessionReservationService.ReservationSpec(
                "owner-1", " tenant-1 ", "codex-worker", "root-session",
                "Forwarded prompt", null, null, "gpt-5");
        var reserved = service.reserve(REQUEST_ID.toUpperCase(), providerSpec);
        SessionEntity row = sessions.findById(reserved.sessionId()).orElseThrow();

        assertThat(row.getTenantId()).isEqualTo("tenant-1");
        assertThat(row.getProviderType()).isEqualTo("codex-worker");
        assertThat(row.getBindingSource()).isEqualTo("EXPLICIT_AGENT");
    }

    @Test
    void publicCapabilityHasNoUpdateDeleteOrScanMethod() {
        assertThat(Arrays.stream(SessionForwardTargetSessionReservationService.class
                        .getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .containsExactly("reserve");
        assertThat(Arrays.stream(SessionForwardTargetSessionReservationRepository.class
                        .getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .containsExactly("insertAndFlush");
    }

    @Test
    void productionAutoConfigurationScansTheInsertOnlyRepositoryCapability() {
        ComponentScan scan = SessionModuleAutoConfiguration.class
                .getAnnotation(ComponentScan.class);

        assertThat(scan.basePackages())
                .contains("com.foggy.navigator.session.repository");
    }

    private SessionForwardTargetSessionReservationService.ReservationResult await(
            Future<SessionForwardTargetSessionReservationService.ReservationResult> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private Object reserveOrFailure(
            CountDownLatch start,
            SessionForwardTargetSessionReservationService.ReservationSpec requested) {
        try {
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return service.reserve(REQUEST_ID, requested);
        } catch (RuntimeException failure) {
            return failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new AssertionError(interrupted);
        }
    }

    private AtomicInteger barrierAtInsert(int callers) {
        CountDownLatch allAtInsert = new CountDownLatch(callers);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            allAtInsert.countDown();
            assertThat(allAtInsert.await(5, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(inserts).insertAndFlush(any(SessionEntity.class));
        return attempts;
    }

    private SessionForwardTargetSessionReservationService.ReservationSpec spec() {
        return newSpec("agent-1", "root-session", "Forwarded prompt", "directory-1",
                "milestone-1", "model-1");
    }

    private SessionForwardTargetSessionReservationService.ReservationSpec newSpec(
            String agentId,
            String rootSessionId,
            String title,
            String directoryId,
            String milestoneId,
            String model) {
        return new SessionForwardTargetSessionReservationService.ReservationSpec(
                "owner-1", "tenant-1", agentId, rootSessionId, title,
                directoryId, milestoneId, model);
    }

    private SessionEntity row(
            String sessionId,
            SessionForwardTargetSessionReservationService.ReservationSpec spec) {
        SessionEntity row = new SessionEntity();
        row.setId(sessionId);
        row.setUserId(spec.ownerUserId());
        row.setTenantId(spec.tenantId());
        row.setAgentId(spec.logicalAgentId());
        row.setParentSessionId(spec.rootParentSessionId());
        row.setTitle(spec.title());
        row.setStatus("ACTIVE");
        row.setInteractionState("PROCESSING");
        row.setCurrentDirectoryId(spec.directoryId());
        row.setMilestoneId(spec.milestoneId());
        row.setLatestModel(spec.model());
        return row;
    }
}
