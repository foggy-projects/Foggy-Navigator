package com.foggy.navigator.session.command;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.session.command.CommandOnceReceiptService.BeginEffectDisposition;
import com.foggy.navigator.session.command.CommandOnceReceiptService.CommandReceiptConflictException;
import com.foggy.navigator.session.command.CommandOnceReceiptService.EffectPermit;
import com.foggy.navigator.session.command.CommandOnceReceiptService.PrepareDisposition;
import com.foggy.navigator.session.command.CommandOnceReceiptService.ReceiptState;
import com.foggy.navigator.session.command.persistence.CommandOnceReceiptEntity;
import com.foggy.navigator.session.command.repository.CommandOnceReceiptRepository;
import com.foggy.navigator.session.config.SessionModuleAutoConfiguration;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope.Actor;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope.ActorKind;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope.CommandBinding;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope.CommandIngress;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope.CommandKind;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope.Effect;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope.Ingress;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope.Ownership;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope.Request;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope.Target;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope.TargetKind;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(CommandOnceReceiptServiceTest.Config.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:command_once_receipt;MODE=MYSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class CommandOnceReceiptServiceTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-03T04:00:00Z");
    private static final Duration VALIDITY = Duration.ofMinutes(5);
    private static final String POLICY =
            SessionModuleAutoConfiguration.CANONICAL_COMMAND_POLICY_VERSION;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EntityScan(basePackageClasses = CommandOnceReceiptEntity.class)
    @EnableJpaRepositories(basePackageClasses = CommandOnceReceiptRepository.class)
    @Import({
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            CommandOnceReceiptService.class
    })
    static class Config {

        @Bean(name = SessionModuleAutoConfiguration.CANONICAL_COMMAND_AUTHORITY_CLOCK)
        MutableClock commandAuthorityClock() {
            return new MutableClock(BASE_TIME, ZoneOffset.UTC);
        }

        @Bean
        VerifiedCommandAuthorizationDecision.ServerAuthority commandAuthority(
                @Qualifier(SessionModuleAutoConfiguration.CANONICAL_COMMAND_AUTHORITY_CLOCK)
                Clock clock) {
            return new VerifiedCommandAuthorizationDecision.ServerAuthority(
                    POLICY, clock, VALIDITY);
        }
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CommandOnceReceiptService service;

    @Autowired
    private CommandOnceReceiptRepository receipts;

    @Autowired
    private VerifiedCommandAuthorizationDecision.ServerAuthority authority;

    @Autowired
    @Qualifier(SessionModuleAutoConfiguration.CANONICAL_COMMAND_AUTHORITY_CLOCK)
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void resetDisposableFixture() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createQuery("delete from CommandOnceReceiptEntity")
                        .executeUpdate());
        clock.set(BASE_TIME);
    }

    @Test
    void productionConfigurationAndMinimalSliceRegisterTheClosedCapability() throws Exception {
        ComponentScan components = SessionModuleAutoConfiguration.class
                .getAnnotation(ComponentScan.class);
        EntityScan entities = SessionModuleAutoConfiguration.class
                .getAnnotation(EntityScan.class);
        EnableJpaRepositories repositories = SessionModuleAutoConfiguration.class
                .getAnnotation(EnableJpaRepositories.class);

        assertThat(components.basePackages())
                .contains("com.foggy.navigator.session.command");
        assertThat(entities.basePackages())
                .contains("com.foggy.navigator.session.command.persistence");
        assertThat(repositories.basePackages())
                .contains("com.foggy.navigator.session.command.repository");

        Method clockBean = SessionModuleAutoConfiguration.class
                .getDeclaredMethod("canonicalCommandAuthorityClock");
        assertThat(clockBean.getAnnotation(Bean.class).value())
                .contains(SessionModuleAutoConfiguration.CANONICAL_COMMAND_AUTHORITY_CLOCK);
        assertThat(clockBean.getAnnotation(ConditionalOnMissingBean.class).name())
                .contains(SessionModuleAutoConfiguration.CANONICAL_COMMAND_AUTHORITY_CLOCK);

        Method authorityBean = SessionModuleAutoConfiguration.class.getDeclaredMethod(
                "canonicalCommandServerAuthority", Clock.class);
        assertThat(authorityBean.getAnnotation(Bean.class)).isNotNull();
        assertThat(authorityBean.getAnnotation(ConditionalOnMissingBean.class).value())
                .containsExactly(VerifiedCommandAuthorizationDecision.ServerAuthority.class);
        assertThat(authorityBean.getParameterAnnotations()[0])
                .anySatisfy(annotation -> assertThat(annotation)
                        .isInstanceOfSatisfying(Qualifier.class, qualifier ->
                                assertThat(qualifier.value()).isEqualTo(
                                        SessionModuleAutoConfiguration
                                                .CANONICAL_COMMAND_AUTHORITY_CLOCK)));

        assertThat(context.getBeansOfType(CommandOnceReceiptService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                VerifiedCommandAuthorizationDecision.ServerAuthority.class)).hasSize(1);
        assertThat(context.getBean(
                SessionModuleAutoConfiguration.CANONICAL_COMMAND_AUTHORITY_CLOCK))
                .isSameAs(clock);
        assertThat(AopUtils.isAopProxy(receipts)).isTrue();
        assertThat(entityManager.getMetamodel().entity(CommandOnceReceiptEntity.class))
                .isNotNull();

        Issued issued = issue(authority, binding("registration"));
        var prepared = service.prepare(issued.envelope(), issued.decision());
        assertThat(prepared.disposition()).isEqualTo(PrepareDisposition.CREATED);
        assertThat(prepared.snapshot().receiptId()).isEqualTo(
                "af683dbc2582a49d75e4dc81c21cd491034ff33a853db79011f3dec8bb682067");
        assertThat(receipts.findByClientRequestId("request-registration"))
                .isPresent();

        Set<String> serviceApi = Arrays.stream(
                        CommandOnceReceiptService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertThat(serviceApi).containsExactlyInAnyOrder(
                "prepare", "beginEffect", "recordResult", "markAmbiguous", "find");
        assertThat(Arrays.stream(CommandOnceReceiptRepository.class.getDeclaredMethods())
                .map(Method::getName))
                .containsExactlyInAnyOrder(
                        "saveAndFlush",
                        "findByClientRequestId",
                        "findByEffectAttemptId",
                        "findByReceiptIdForUpdate");

        assertThat(EffectPermit.class.isRecord()).isFalse();
        assertThat(Arrays.stream(EffectPermit.class.getDeclaredConstructors()))
                .allSatisfy(constructor -> assertThat(
                        Modifier.isPrivate(constructor.getModifiers())).isTrue());
        assertThat(Arrays.stream(EffectPermit.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers())))
                .isEmpty();
        assertThat(Arrays.stream(CommandOnceReceiptService.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .noneMatch(Supplier.class::isAssignableFrom)).isTrue();
        assertThat(Arrays.stream(CommandOnceReceiptService.class.getDeclaredFields())
                .map(field -> field.getType().getName().toLowerCase())
                .noneMatch(type -> type.contains("provider")
                        || type.contains("outbox")
                        || type.contains("token")
                        || type.contains("scanner"))).isTrue();
    }

    @Test
    void bindingDigestCoversEveryEnvelopeFactAndKeepsClientRequestAsOnceIdentity() {
        CommandBinding original = binding("binding-matrix");
        Issued first = issue(authority, original);
        var prepared = service.prepare(first.envelope(), first.decision());

        assertThat(prepared.disposition()).isEqualTo(PrepareDisposition.CREATED);
        for (BindingVariant variant : bindingVariants(original)) {
            Issued changed = issue(authority, variant.binding());
            assertThatThrownBy(() -> service.prepare(
                    changed.envelope(), changed.decision()))
                    .as(variant.field())
                    .isInstanceOf(CommandReceiptConflictException.class)
                    .hasMessage("COMMAND_RECEIPT_BINDING_CONFLICT");
        }

        CommandBinding serverActor = withActor(
                binding("server-actor"),
                new Actor(ActorKind.SERVER_PROCESS, null, null, null, "authority-a"));
        Issued serverFirst = issue(authority, serverActor);
        service.prepare(serverFirst.envelope(), serverFirst.decision());
        Issued changedServerReference = issue(authority, withActor(
                serverActor,
                new Actor(ActorKind.SERVER_PROCESS, null, null, null, "authority-b")));
        assertThatThrownBy(() -> service.prepare(
                changedServerReference.envelope(), changedServerReference.decision()))
                .isInstanceOf(CommandReceiptConflictException.class)
                .hasMessage("COMMAND_RECEIPT_BINDING_CONFLICT");

        Issued distinctIdentity = issue(authority, binding("binding-matrix-other"));
        var second = service.prepare(
                distinctIdentity.envelope(), distinctIdentity.decision());
        assertThat(second.disposition()).isEqualTo(PrepareDisposition.CREATED);
        assertThat(second.snapshot().receiptId())
                .isNotEqualTo(prepared.snapshot().receiptId());
        assertThat(rowCount()).isEqualTo(3);

        assertThatThrownBy(() -> new CanonicalCommandEnvelope(
                "navi.command-envelope.v2",
                original,
                first.decision().metadata()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prepareRequiresTheCurrentAuthorityAndAcceptsOnlyStableRenewal() {
        CommandBinding binding = binding("renewal");
        Issued original = issue(authority, binding);
        var created = service.prepare(original.envelope(), original.decision());
        CommandOnceReceiptEntity firstRow = receipts
                .findByClientRequestId("request-renewal")
                .orElseThrow();

        assertThat(service.prepare(original.envelope(), original.decision()).disposition())
                .isEqualTo(PrepareDisposition.EXACT_REPLAY);

        Issued sameAuthorityRenewal = issue(authority, binding);
        assertThat(service.prepare(
                sameAuthorityRenewal.envelope(), sameAuthorityRenewal.decision()).disposition())
                .isEqualTo(PrepareDisposition.AUTHORIZATION_RENEWAL_ACCEPTED);
        assertFirstProvenanceUnchanged(firstRow, original);

        var foreignAuthority = new VerifiedCommandAuthorizationDecision.ServerAuthority(
                POLICY, clock, VALIDITY);
        Issued foreignDecision = issue(foreignAuthority, binding);
        assertThatThrownBy(() -> service.prepare(
                foreignDecision.envelope(), foreignDecision.decision()))
                .isInstanceOf(SecurityException.class);

        var changedPolicyAuthority = new VerifiedCommandAuthorizationDecision.ServerAuthority(
                "navi.command-receipt-policy.v2", clock, VALIDITY);
        CommandOnceReceiptService changedPolicyService =
                newService(changedPolicyAuthority);
        Issued changedPolicy = issue(changedPolicyAuthority, binding);
        assertThatThrownBy(() -> changedPolicyService.prepare(
                changedPolicy.envelope(), changedPolicy.decision()))
                .isInstanceOf(CommandReceiptConflictException.class)
                .hasMessage("COMMAND_RECEIPT_AUTHORIZATION_CONFLICT");

        clock.advance(VALIDITY.plusSeconds(1));
        assertThatThrownBy(() -> service.prepare(
                original.envelope(), original.decision()))
                .isInstanceOf(SecurityException.class);

        var restartedAuthority = new VerifiedCommandAuthorizationDecision.ServerAuthority(
                POLICY, clock, VALIDITY);
        CommandOnceReceiptService restarted = newService(restartedAuthority);
        Issued currentRenewal = issue(restartedAuthority, binding);
        assertThat(restarted.prepare(
                currentRenewal.envelope(), currentRenewal.decision()).disposition())
                .isEqualTo(PrepareDisposition.AUTHORIZATION_RENEWAL_ACCEPTED);

        CommandOnceReceiptEntity afterRenewal = receipts
                .findByClientRequestId("request-renewal")
                .orElseThrow();
        assertFirstProvenanceUnchanged(afterRenewal, original);
        assertThat(afterRenewal.getBindingDigest()).isEqualTo(firstRow.getBindingDigest());
        assertThat(afterRenewal.getAuthorizationBindingDigest())
                .isEqualTo(firstRow.getAuthorizationBindingDigest());
        assertThat(created.snapshot().initialAuthorizationDecisionId())
                .isEqualTo(original.decision().metadata().decisionId());
    }

    @Test
    void concurrentPrepareCreatesOneRowAndConcurrentBeginGrantsOnePermit() throws Exception {
        CommandBinding binding = binding("concurrent");
        Issued first = issue(authority, binding);
        Issued second = issue(authority, binding);
        FirstMissingBarrierRepository barrierRepository =
                new FirstMissingBarrierRepository(receipts);
        CommandOnceReceiptService firstService = new CommandOnceReceiptService(
                barrierRepository, authority, clock, transactionManager);
        CommandOnceReceiptService secondService = new CommandOnceReceiptService(
                barrierRepository, authority, clock, transactionManager);

        List<CommandOnceReceiptService.PrepareResult> prepareResults = concurrently(
                () -> firstService.prepare(first.envelope(), first.decision()),
                () -> secondService.prepare(second.envelope(), second.decision()));

        assertThat(prepareResults)
                .filteredOn(result -> result.disposition() == PrepareDisposition.CREATED)
                .hasSize(1);
        assertThat(prepareResults)
                .filteredOn(result -> result.disposition()
                        == PrepareDisposition.AUTHORIZATION_RENEWAL_ACCEPTED)
                .hasSize(1);
        assertThat(rowCount()).isEqualTo(1);

        List<EffectPermit> permits = concurrently(
                () -> service.beginEffect(first.envelope(), first.decision()),
                () -> service.beginEffect(second.envelope(), second.decision()));
        assertThat(permits).filteredOn(EffectPermit::providerEffectPermitted).hasSize(1);
        assertThat(permits)
                .filteredOn(permit -> permit.disposition()
                        == BeginEffectDisposition.ALREADY_STARTED)
                .hasSize(1);
        assertThat(permits)
                .extracting(permit -> permit.snapshot().effectAttemptId())
                .doesNotContainNull()
                .containsOnly(permits.get(0).snapshot().effectAttemptId());
        assertThat(receipts.findByEffectAttemptId(
                permits.get(0).snapshot().effectAttemptId())).isPresent();
    }

    @Test
    void managementFenceRunsInsidePrepareReplayAndBeginWriteTransactions() {
        ControllableManagementFence fence = new ControllableManagementFence();
        CommandOnceReceiptService fenced = new CommandOnceReceiptService(
                receipts, authority, clock, transactionManager, List.of(fence));
        Issued issued = issue(authority, managementBinding("domain-drift"));

        assertThat(fenced.prepare(issued.envelope(), issued.decision()).disposition())
                .isEqualTo(PrepareDisposition.CREATED);
        fence.reject("TERMINATION_MANAGEMENT_DOMAIN_NOT_NON_ENFORCED");

        assertThatThrownBy(() -> fenced.prepare(
                issued.envelope(), issued.decision()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TERMINATION_MANAGEMENT_DOMAIN_NOT_NON_ENFORCED");
        assertThatThrownBy(() -> fenced.beginEffect(
                issued.envelope(), issued.decision()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TERMINATION_MANAGEMENT_DOMAIN_NOT_NON_ENFORCED");

        assertThat(fence.calls()).isEqualTo(3);
        assertThat(service.find("request-management-domain-drift"))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.state())
                        .isEqualTo(ReceiptState.PREPARED));
    }

    @Test
    void recordedManagementResultReplaysWithoutCurrentDomainRecheck() {
        ControllableManagementFence fence = new ControllableManagementFence();
        CommandOnceReceiptService fenced = new CommandOnceReceiptService(
                receipts, authority, clock, transactionManager, List.of(fence));
        Issued issued = issue(authority, managementBinding("recorded-domain"));
        fenced.prepare(issued.envelope(), issued.decision());
        EffectPermit permit = fenced.beginEffect(issued.envelope(), issued.decision());
        fenced.recordResult(
                issued.envelope().binding().request().clientRequestId(),
                permit.snapshot().effectAttemptId(),
                "TASK:task-recorded-domain",
                "TERMINATION_REQUEST_ACCEPTED");
        assertThat(fence.calls()).isEqualTo(2);
        fence.reject("TERMINATION_MANAGEMENT_DOMAIN_NOT_NON_ENFORCED");

        assertThat(fenced.prepare(
                issued.envelope(), issued.decision()).snapshot().state())
                .isEqualTo(ReceiptState.RESULT_RECORDED);
        assertThat(fenced.beginEffect(
                issued.envelope(), issued.decision()).disposition())
                .isEqualTo(BeginEffectDisposition.RESULT_RECORDED);
        assertThat(fence.calls()).isEqualTo(2);
    }

    @Test
    void fourArgumentConstructorFailsClosedForManagementRoute() {
        CommandOnceReceiptService unfenced = new CommandOnceReceiptService(
                receipts, authority, clock, transactionManager);
        Issued issued = issue(authority, managementBinding("missing-fence"));

        assertThatThrownBy(() -> unfenced.prepare(
                issued.envelope(), issued.decision()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TERMINATION_MANAGEMENT_DOMAIN_FENCE_MISSING");
        assertThat(rowCount()).isZero();
    }

    @Test
    void duplicateManagementPrepareRecoveryReentersWriteFence() throws Exception {
        CommandBinding binding = managementBinding("duplicate-fence");
        Issued first = issue(authority, binding);
        Issued second = issue(authority, binding);
        FirstMissingBarrierRepository barrierRepository =
                new FirstMissingBarrierRepository(receipts);
        ControllableManagementFence fence = new ControllableManagementFence();
        CommandOnceReceiptService firstService = new CommandOnceReceiptService(
                barrierRepository, authority, clock, transactionManager, List.of(fence));
        CommandOnceReceiptService secondService = new CommandOnceReceiptService(
                barrierRepository, authority, clock, transactionManager, List.of(fence));

        List<CommandOnceReceiptService.PrepareResult> results = concurrently(
                () -> firstService.prepare(first.envelope(), first.decision()),
                () -> secondService.prepare(second.envelope(), second.decision()));

        assertThat(results)
                .filteredOn(result -> result.disposition() == PrepareDisposition.CREATED)
                .hasSize(1);
        assertThat(results)
                .filteredOn(result -> result.disposition()
                        == PrepareDisposition.AUTHORIZATION_RENEWAL_ACCEPTED)
                .hasSize(1);
        assertThat(fence.calls()).isEqualTo(3);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void startedReceiptNeverReplaysAndTerminalUpdatesAreExactWithoutLiveDecision() {
        CommandBinding ambiguousBinding = binding("ambiguous");
        Issued ambiguousIssued = issue(authority, ambiguousBinding);
        service.prepare(ambiguousIssued.envelope(), ambiguousIssued.decision());
        EffectPermit firstPermit = service.beginEffect(
                ambiguousIssued.envelope(), ambiguousIssued.decision());

        var restartedAuthority = new VerifiedCommandAuthorizationDecision.ServerAuthority(
                POLICY, clock, VALIDITY);
        CommandOnceReceiptService restarted = newService(restartedAuthority);
        Issued restartedDecision = issue(restartedAuthority, ambiguousBinding);
        EffectPermit replay = restarted.beginEffect(
                restartedDecision.envelope(), restartedDecision.decision());
        assertThat(replay.disposition()).isEqualTo(BeginEffectDisposition.ALREADY_STARTED);
        assertThat(replay.providerEffectPermitted()).isFalse();
        assertThat(replay.snapshot().effectAttemptId())
                .isEqualTo(firstPermit.snapshot().effectAttemptId());

        clock.advance(VALIDITY.plusSeconds(1));
        var ambiguous = restarted.markAmbiguous(
                "request-ambiguous",
                firstPermit.snapshot().effectAttemptId(),
                "PROVIDER_OUTCOME_UNKNOWN");
        assertThat(ambiguous.state()).isEqualTo(ReceiptState.AMBIGUOUS);
        assertThat(restarted.markAmbiguous(
                "request-ambiguous",
                firstPermit.snapshot().effectAttemptId(),
                "PROVIDER_OUTCOME_UNKNOWN")).isEqualTo(ambiguous);
        assertThatThrownBy(() -> restarted.markAmbiguous(
                "request-ambiguous",
                firstPermit.snapshot().effectAttemptId(),
                "DIFFERENT_CODE"))
                .isInstanceOf(CommandReceiptConflictException.class)
                .hasMessage("COMMAND_RECEIPT_RESULT_CONFLICT");
        assertThatThrownBy(() -> restarted.recordResult(
                "request-ambiguous",
                firstPermit.snapshot().effectAttemptId(),
                "TASK:too-late",
                "CREATED"))
                .isInstanceOf(CommandReceiptConflictException.class)
                .hasMessage("COMMAND_RECEIPT_STATE_CONFLICT");

        Issued afterRestart = issue(restartedAuthority, ambiguousBinding);
        assertThat(restarted.beginEffect(
                afterRestart.envelope(), afterRestart.decision()).disposition())
                .isEqualTo(BeginEffectDisposition.AMBIGUOUS);

        CommandBinding resultBinding = binding("result");
        Issued resultIssued = issue(restartedAuthority, resultBinding);
        restarted.prepare(resultIssued.envelope(), resultIssued.decision());
        EffectPermit resultPermit = restarted.beginEffect(
                resultIssued.envelope(), resultIssued.decision());
        String attempt = resultPermit.snapshot().effectAttemptId();
        assertThatThrownBy(() -> restarted.recordResult(
                "request-result", "wrong-attempt", "TASK:task-result", "CREATED"))
                .isInstanceOf(CommandReceiptConflictException.class)
                .hasMessage("COMMAND_RECEIPT_ATTEMPT_MISMATCH");

        clock.advance(VALIDITY.plusSeconds(1));
        var result = restarted.recordResult(
                "request-result", attempt, "TASK:task-result", "CREATED");
        assertThat(result.state()).isEqualTo(ReceiptState.RESULT_RECORDED);
        assertThat(restarted.recordResult(
                "request-result", attempt, "TASK:task-result", "CREATED"))
                .isEqualTo(result);
        assertThatThrownBy(() -> restarted.recordResult(
                "request-result", attempt, "TASK:different", "CREATED"))
                .isInstanceOf(CommandReceiptConflictException.class)
                .hasMessage("COMMAND_RECEIPT_RESULT_CONFLICT");
        assertThatThrownBy(() -> restarted.markAmbiguous(
                "request-result", attempt, "PROVIDER_OUTCOME_UNKNOWN"))
                .isInstanceOf(CommandReceiptConflictException.class)
                .hasMessage("COMMAND_RECEIPT_STATE_CONFLICT");

        Issued currentResultDecision = issue(restartedAuthority, resultBinding);
        assertThat(restarted.beginEffect(
                currentResultDecision.envelope(),
                currentResultDecision.decision()).disposition())
                .isEqualTo(BeginEffectDisposition.RESULT_RECORDED);
    }

    @Test
    void everyStateWriteCommitsIndependentlyOfAnOuterRollback() {
        CommandBinding binding = binding("outer-rollback");
        Issued issued = issue(authority, binding);

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            service.prepare(issued.envelope(), issued.decision());
            EffectPermit permit = service.beginEffect(
                    issued.envelope(), issued.decision());
            service.recordResult(
                    "request-outer-rollback",
                    permit.snapshot().effectAttemptId(),
                    "TASK:outer-rollback",
                    "CREATED");
            assertThat(service.find("request-outer-rollback")).isPresent();
            status.setRollbackOnly();
        });

        assertThat(service.find("request-outer-rollback"))
                .hasValueSatisfying(receipt -> {
                    assertThat(receipt.state()).isEqualTo(ReceiptState.RESULT_RECORDED);
                    assertThat(receipt.opaqueResultReference())
                            .isEqualTo("TASK:outer-rollback");
                });
    }

    private CommandOnceReceiptService newService(
            VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority) {
        return new CommandOnceReceiptService(
                receipts, serverAuthority, clock, transactionManager);
    }

    private long rowCount() {
        Long count = new TransactionTemplate(transactionManager).execute(status ->
                entityManager.createQuery(
                                "select count(receipt) from CommandOnceReceiptEntity receipt",
                                Long.class)
                        .getSingleResult());
        return count == null ? 0 : count;
    }

    private static void assertFirstProvenanceUnchanged(
            CommandOnceReceiptEntity receipt,
            Issued original) {
        assertThat(receipt.getAuthorizationDecisionId())
                .isEqualTo(original.decision().metadata().decisionId());
        assertThat(receipt.getAuthorizationIssuedAt())
                .isEqualTo(original.decision().metadata().issuedAt());
        assertThat(receipt.getAuthorizationNotBefore())
                .isEqualTo(original.decision().metadata().notBefore());
        assertThat(receipt.getAuthorizationExpiresAt())
                .isEqualTo(original.decision().metadata().expiresAt());
    }

    private static List<BindingVariant> bindingVariants(CommandBinding binding) {
        Ingress ingress = binding.ingress();
        Request request = binding.request();
        Actor actor = binding.actor();
        Ownership ownership = binding.ownership();
        Target target = binding.target();
        Effect effect = binding.effect();
        return List.of(
                variant("commandKind", withKind(binding, CommandKind.TERMINATE)),
                variant("ingress", withIngress(binding, new Ingress(
                        CommandIngress.DIRECT, ingress.clientSurface(), ingress.routeId()))),
                variant("clientSurface", withIngress(binding, new Ingress(
                        ingress.ingress(), "surface-other", ingress.routeId()))),
                variant("routeId", withIngress(binding, new Ingress(
                        ingress.ingress(), ingress.clientSurface(), "route-other"))),
                variant("idempotencyKey", withRequest(binding, new Request(
                        request.clientRequestId(), "idempotency-other", request.correlationId()))),
                variant("correlationId", withRequest(binding, new Request(
                        request.clientRequestId(), request.idempotencyKey(), "correlation-other"))),
                variant("actorKind", withActor(binding, new Actor(
                        ActorKind.SERVER_PROCESS, null, null, null, "authority-other"))),
                variant("principalType", withActor(binding, new Actor(
                        actor.kind(), AuthorizationPrincipalType.CLIENT_APP, actor.lane(),
                        actor.fingerprint(), null))),
                variant("credentialLane", withActor(binding, new Actor(
                        actor.kind(), actor.principalType(),
                        AuthorizationCredentialLane.CLIENT_APP_CONTROL,
                        actor.fingerprint(), null))),
                variant("fingerprint", withActor(binding, new Actor(
                        actor.kind(), actor.principalType(), actor.lane(),
                        "fingerprint-other", null))),
                variant("tenantReference", withOwnership(binding, new Ownership(
                        "tenant-other", ownership.ownerReference(),
                        ownership.clientAppReference(), ownership.upstreamReference()))),
                variant("ownerReference", withOwnership(binding, new Ownership(
                        ownership.tenantReference(), "owner-other",
                        ownership.clientAppReference(), ownership.upstreamReference()))),
                variant("clientAppReference-null-tag", withOwnership(binding, new Ownership(
                        ownership.tenantReference(), ownership.ownerReference(), null,
                        ownership.upstreamReference()))),
                variant("upstreamReference-null-tag", withOwnership(binding, new Ownership(
                        ownership.tenantReference(), ownership.ownerReference(),
                        ownership.clientAppReference(), null))),
                variant("targetKind", withTarget(binding, new Target(
                        TargetKind.APPROVAL, target.targetId(), target.logicalAgentId(),
                        target.providerType(), target.physicalWorkerId(), target.modelConfigId(),
                        target.taskId(), target.sessionId()))),
                variant("targetId", withTarget(binding, new Target(
                        target.kind(), "runtime-other", target.logicalAgentId(),
                        target.providerType(), target.physicalWorkerId(), target.modelConfigId(),
                        target.taskId(), target.sessionId()))),
                variant("logicalAgentId", withTarget(binding, new Target(
                        target.kind(), target.targetId(), null, target.providerType(),
                        target.physicalWorkerId(), target.modelConfigId(), target.taskId(),
                        target.sessionId()))),
                variant("providerType", withTarget(binding, new Target(
                        target.kind(), target.targetId(), target.logicalAgentId(),
                        "provider-other", target.physicalWorkerId(), target.modelConfigId(),
                        target.taskId(), target.sessionId()))),
                variant("physicalWorkerId", withTarget(binding, new Target(
                        target.kind(), target.targetId(), target.logicalAgentId(),
                        target.providerType(), null, target.modelConfigId(), target.taskId(),
                        target.sessionId()))),
                variant("modelConfigId", withTarget(binding, new Target(
                        target.kind(), target.targetId(), target.logicalAgentId(),
                        target.providerType(), target.physicalWorkerId(), null, target.taskId(),
                        target.sessionId()))),
                variant("taskId", withTarget(binding, new Target(
                        target.kind(), target.targetId(), target.logicalAgentId(),
                        target.providerType(), target.physicalWorkerId(), target.modelConfigId(),
                        null, target.sessionId()))),
                variant("sessionId", withTarget(binding, new Target(
                        target.kind(), target.targetId(), target.logicalAgentId(),
                        target.providerType(), target.physicalWorkerId(), target.modelConfigId(),
                        target.taskId(), null))),
                variant("actionId", withEffect(binding, new Effect(
                        "action-other", effect.effectScopeReference()))),
                variant("effectScopeReference", withEffect(binding, new Effect(
                        effect.actionId(), "scope-other"))));
    }

    private static BindingVariant variant(String field, CommandBinding binding) {
        return new BindingVariant(field, binding);
    }

    private static CommandBinding binding(String suffix) {
        return new CommandBinding(
                CommandKind.CREATE,
                new Ingress(CommandIngress.A2A, "workers", "route-" + suffix),
                new Request(
                        "request-" + suffix,
                        "idempotency-" + suffix,
                        "correlation-" + suffix),
                new Actor(
                        ActorKind.AUTHENTICATED_PRINCIPAL,
                        AuthorizationPrincipalType.NAVIGATOR_USER,
                        AuthorizationCredentialLane.NAVIGATOR_JWT,
                        "fingerprint-" + suffix,
                        null),
                new Ownership(
                        "tenant-" + suffix,
                        "owner-" + suffix,
                        "client-app-" + suffix,
                        "upstream-" + suffix),
                new Target(
                        TargetKind.RUNTIME,
                        "runtime-" + suffix,
                        "logical-" + suffix,
                        "codex-worker",
                        "worker-" + suffix,
                        "model-" + suffix,
                        "task-" + suffix,
                        "session-" + suffix),
                new Effect("create-task", "scope-" + suffix));
    }

    private static CommandBinding managementBinding(String suffix) {
        String requestId = "request-management-" + suffix;
        String taskId = "task-" + suffix;
        return new CommandBinding(
                CommandKind.TERMINATE,
                new Ingress(
                        CommandIngress.OPENAPI,
                        CommandReceiptTransactionFence.OPEN_API_CLIENT_SURFACE,
                        CommandReceiptTransactionFence
                                .OPEN_API_AGENT_TASK_CANCEL_ROUTE),
                new Request(requestId, requestId, requestId),
                new Actor(
                        ActorKind.AUTHENTICATED_PRINCIPAL,
                        AuthorizationPrincipalType.NAVIGATOR_USER,
                        AuthorizationCredentialLane.NAVIGATOR_JWT,
                        "management-fingerprint-" + suffix,
                        null),
                new Ownership(
                        "navi.tenant.present.v1:tenant-1",
                        "durable-owner",
                        null,
                        null),
                new Target(
                        TargetKind.TASK,
                        taskId,
                        "agent-1",
                        "codex-worker",
                        "worker-1",
                        "model-config-1",
                        taskId,
                        "session-1"),
                new Effect(
                        CommandReceiptTransactionFence.TASK_TERMINATE_ACTION,
                        "termination-scope-" + suffix));
    }

    private static CommandBinding withKind(CommandBinding value, CommandKind kind) {
        return new CommandBinding(kind, value.ingress(), value.request(), value.actor(),
                value.ownership(), value.target(), value.effect());
    }

    private static CommandBinding withIngress(CommandBinding value, Ingress ingress) {
        return new CommandBinding(value.commandKind(), ingress, value.request(), value.actor(),
                value.ownership(), value.target(), value.effect());
    }

    private static CommandBinding withRequest(CommandBinding value, Request request) {
        return new CommandBinding(value.commandKind(), value.ingress(), request, value.actor(),
                value.ownership(), value.target(), value.effect());
    }

    private static CommandBinding withActor(CommandBinding value, Actor actor) {
        return new CommandBinding(value.commandKind(), value.ingress(), value.request(), actor,
                value.ownership(), value.target(), value.effect());
    }

    private static CommandBinding withOwnership(CommandBinding value, Ownership ownership) {
        return new CommandBinding(value.commandKind(), value.ingress(), value.request(),
                value.actor(), ownership, value.target(), value.effect());
    }

    private static CommandBinding withTarget(CommandBinding value, Target target) {
        return new CommandBinding(value.commandKind(), value.ingress(), value.request(),
                value.actor(), value.ownership(), target, value.effect());
    }

    private static CommandBinding withEffect(CommandBinding value, Effect effect) {
        return new CommandBinding(value.commandKind(), value.ingress(), value.request(),
                value.actor(), value.ownership(), value.target(), effect);
    }

    private static Issued issue(
            VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority,
            CommandBinding binding) {
        VerifiedCommandAuthorizationDecision decision = serverAuthority.issue(binding);
        return new Issued(new CanonicalCommandEnvelope(
                CanonicalCommandEnvelope.SCHEMA_VERSION,
                binding,
                decision.metadata()), decision);
    }

    private static <T> List<T> concurrently(
            Callable<T> first,
            Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> firstFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return first.call();
            });
            Future<T> secondFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return second.call();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    firstFuture.get(15, TimeUnit.SECONDS),
                    secondFuture.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private record Issued(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
    }

    private record BindingVariant(String field, CommandBinding binding) {
    }

    private static final class ControllableManagementFence
            implements CommandReceiptTransactionFence {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<LockedDomain> result =
                new AtomicReference<>(LockedDomain.allowed());

        @Override
        public boolean claims(CommandBinding binding) {
            return CommandReceiptTransactionFence
                    .requiresOpenApiAgentTaskTerminationFence(binding);
        }

        @Override
        public LockedDomain lock(CommandBinding binding) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                throw new AssertionError(
                        "management fence requires an active write transaction");
            }
            calls.incrementAndGet();
            return result.get();
        }

        void reject(String safeCode) {
            result.set(LockedDomain.rejected(safeCode));
        }

        int calls() {
            return calls.get();
        }
    }

    /**
     * Forces the two initial reads to observe a missing row. For fenced prepare it also makes the
     * first two receipt-lock lookups return missing together, deterministically exercising the
     * duplicate-insert recovery path instead of allowing either valid database scheduling. All
     * writes still use the actual Spring Data proxy, so the losing save must exit its failed
     * transaction before the service performs recovery in a new transaction.
     */
    private static final class FirstMissingBarrierRepository
            implements CommandOnceReceiptRepository {

        private final CommandOnceReceiptRepository delegate;
        private final CountDownLatch bothInitialReadsCompleted = new CountDownLatch(2);
        private final CountDownLatch bothInitialReceiptLocksCompleted =
                new CountDownLatch(2);

        private FirstMissingBarrierRepository(CommandOnceReceiptRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public <S extends CommandOnceReceiptEntity> S saveAndFlush(S entity) {
            return delegate.saveAndFlush(entity);
        }

        @Override
        public Optional<CommandOnceReceiptEntity> findByClientRequestId(
                String clientRequestId) {
            Optional<CommandOnceReceiptEntity> found =
                    delegate.findByClientRequestId(clientRequestId);
            if (bothInitialReadsCompleted.getCount() > 0) {
                if (found.isPresent()) {
                    throw new AssertionError("initial concurrent lookup must observe no row");
                }
                bothInitialReadsCompleted.countDown();
                try {
                    if (!bothInitialReadsCompleted.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("concurrent lookup barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("concurrent lookup barrier interrupted", interrupted);
                }
            }
            return found;
        }

        @Override
        public Optional<CommandOnceReceiptEntity> findByEffectAttemptId(
                String effectAttemptId) {
            return delegate.findByEffectAttemptId(effectAttemptId);
        }

        @Override
        public Optional<CommandOnceReceiptEntity> findByReceiptIdForUpdate(String receiptId) {
            if (bothInitialReceiptLocksCompleted.getCount() > 0) {
                bothInitialReceiptLocksCompleted.countDown();
                try {
                    if (!bothInitialReceiptLocksCompleted.await(
                            5, TimeUnit.SECONDS)) {
                        throw new AssertionError(
                                "concurrent receipt-lock barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                            "concurrent receipt-lock barrier interrupted", interrupted);
                }
                return Optional.empty();
            }
            return delegate.findByReceiptIdForUpdate(receiptId);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        MutableClock(Instant initial, ZoneId zone) {
            this(new AtomicReference<>(initial), zone);
        }

        private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        void set(Instant value) {
            current.set(value);
        }

        void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return requestedZone.equals(zone)
                    ? this
                    : new MutableClock(current, requestedZone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
