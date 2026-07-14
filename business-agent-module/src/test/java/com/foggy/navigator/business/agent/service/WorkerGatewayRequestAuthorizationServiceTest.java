package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.config.WorkerGatewayProperties;
import com.foggy.navigator.business.agent.model.dto.BizWorkerPrincipal;
import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkerGatewayRequestAuthorizationServiceTest {

    private static final String TASK_TOKEN = "btt_task_secret";
    private static final String WORKER_ID = "worker-1";
    private static final String WORKER_CREDENTIAL = "bwc_worker_secret";
    private static final String LEASE_ID = "bwl_task_lease";

    @Mock
    private BusinessAgentTaskService taskService;
    @Mock
    private BusinessTaskScopedTokenPolicyService tokenPolicyService;
    @Mock
    private BizWorkerCredentialService credentialService;
    @Mock
    private ClientAppRepository clientAppRepository;
    @Mock
    private BizWorkerPoolRepository workerPoolRepository;
    @Mock
    private BizWorkerPoolMemberRepository workerPoolMemberRepository;

    private WorkerGatewayProperties properties;
    private WorkerGatewayRequestAuthorizationService service;
    private BusinessTaskScopedTokenDTO token;

    @BeforeEach
    void setUp() {
        properties = new WorkerGatewayProperties();
        service = new WorkerGatewayRequestAuthorizationService(
                properties,
                taskService,
                tokenPolicyService,
                credentialService,
                clientAppRepository,
                workerPoolRepository,
                workerPoolMemberRepository);
        token = token();
    }

    @Test
    void internalDevAllowsOnlyTheCompletelyHeaderlessCompatibilityPath() {
        when(taskService.resolveTaskScopedToken(TASK_TOKEN)).thenReturn(token);

        BusinessTaskScopedTokenDTO result = service.authorize(
                TASK_TOKEN, null, null, null, null);

        assertThat(result).isSameAs(token);
        verify(tokenPolicyService).requireGatewayToken(token);
        verifyNoInteractions(credentialService, clientAppRepository,
                workerPoolRepository, workerPoolMemberRepository);
    }

    @Test
    void externalModeRejectsCompletelyMissingWorkerHeadersBeforeTokenLookup() {
        properties.setExternalEnabled(true);

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, null, null, null, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker credential is required");

        verifyNoInteractions(taskService, credentialService);
    }

    @ParameterizedTest
    @MethodSource("partialOrBlankHeaders")
    void partialOrBlankHeadersAlwaysFailClosed(
            String workerId, String credential, String leaseId) {
        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, workerId, credential, leaseId, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker credential is required");

        verifyNoInteractions(taskService, credentialService);
    }

    @Test
    void legacyWorkerIdHeaderIsNeverAccepted() {
        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, null, null, null, "legacy-worker"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker credential is required");

        verifyNoInteractions(taskService, credentialService);
    }

    @Test
    void completeHeadersAlwaysUseStrictCredentialEvenInInternalMode() {
        stubValidUpstreamBinding();

        BusinessTaskScopedTokenDTO result = service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null);

        assertThat(result).isSameAs(token);
        verify(credentialService).requireStrictCredential(WORKER_ID, WORKER_CREDENTIAL);
    }

    @Test
    void completeHeadersUseTheSameStrictPathInExternalMode() {
        properties.setExternalEnabled(true);
        stubValidUpstreamBinding();

        BusinessTaskScopedTokenDTO result = service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null);

        assertThat(result).isSameAs(token);
        verify(credentialService).requireStrictCredential(WORKER_ID, WORKER_CREDENTIAL);
    }

    @Test
    void strictCredentialFailureDoesNotFallBackOrExposeTheSecret() {
        when(credentialService.requireStrictCredential(WORKER_ID, WORKER_CREDENTIAL))
                .thenThrow(new SecurityException("invalid worker credential"));

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("invalid worker credential")
                .hasMessageNotContaining(WORKER_CREDENTIAL);

        verifyNoInteractions(taskService, clientAppRepository,
                workerPoolRepository, workerPoolMemberRepository);
    }

    @Test
    void strictBindingRejectsWorkerOrLeaseMismatchBeforeResourceLookups() {
        when(credentialService.requireStrictCredential(WORKER_ID, WORKER_CREDENTIAL))
                .thenReturn(principal(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1"));
        when(taskService.resolveTaskScopedToken(TASK_TOKEN)).thenReturn(token);
        token.setWorkerLeaseId("another-lease");

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");

        verifyNoInteractions(clientAppRepository, workerPoolRepository, workerPoolMemberRepository);
    }

    @Test
    void strictBindingRejectsTokenWorkerMismatchBeforeResourceLookups() {
        when(credentialService.requireStrictCredential(WORKER_ID, WORKER_CREDENTIAL))
                .thenReturn(principal(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1"));
        when(taskService.resolveTaskScopedToken(TASK_TOKEN)).thenReturn(token);
        token.setWorkerId("another-worker");

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");

        verifyNoInteractions(clientAppRepository, workerPoolRepository, workerPoolMemberRepository);
    }

    @Test
    void strictBindingRequiresActiveClientAppWithExactUpstreamOwner() {
        stubCredentialAndToken(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1");
        ClientAppEntity app = clientApp("upstream-2");
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(app));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1")));
        when(workerPoolMemberRepository.findByPoolIdAndWorkerId("pool-1", WORKER_ID))
                .thenReturn(Optional.of(member()));

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");
    }

    @Test
    void strictBindingRejectsBackendMismatch() {
        stubCredentialAndToken(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        BizWorkerPoolEntity pool = pool(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1");
        pool.setWorkerBackend("another-backend");
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool));
        when(workerPoolMemberRepository.findByPoolIdAndWorkerId("pool-1", WORKER_ID))
                .thenReturn(Optional.of(member()));

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");
    }

    @Test
    void strictBindingRejectsPersistedPoolTenantMismatch() {
        stubCredentialAndToken(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        BizWorkerPoolEntity pool = pool(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1");
        pool.setTenantId("another-tenant");
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool));

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");

        verifyNoInteractions(workerPoolMemberRepository);
    }

    @Test
    void strictBindingRejectsDisabledPoolBeforeMemberLookup() {
        stubCredentialAndToken(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        BizWorkerPoolEntity pool = pool(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1");
        pool.setStatus(BizWorkerPoolService.STATUS_DISABLED);
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool));

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");

        verifyNoInteractions(workerPoolMemberRepository);
    }

    @Test
    void strictBindingRejectsDisabledPoolMember() {
        stubCredentialAndToken(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1")));
        BizWorkerPoolMemberEntity member = member();
        member.setStatus(BizWorkerPoolService.STATUS_DISABLED);
        when(workerPoolMemberRepository.findByPoolIdAndWorkerId("pool-1", WORKER_ID))
                .thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");
    }

    @Test
    void upstreamPrincipalRequiresExactPoolOwner() {
        stubCredentialAndToken(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-2")));
        when(workerPoolMemberRepository.findByPoolIdAndWorkerId("pool-1", WORKER_ID))
                .thenReturn(Optional.of(member()));

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");
    }

    @Test
    void strictBindingAcceptsOnlyCanonicalPlatformOwnerAsSharedInfrastructure() {
        stubCredentialAndToken(ResourceOwnerType.PLATFORM, BizWorkerPoolService.PLATFORM_OWNER_ID);
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        BizWorkerPoolEntity pool = pool(
                ResourceOwnerType.PLATFORM, "tenant-1");
        pool.setHealthStatus(BizWorkerPoolService.UNHEALTHY);
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool));
        when(workerPoolMemberRepository.findByPoolIdAndWorkerId("pool-1", WORKER_ID))
                .thenReturn(Optional.of(member()));

        BusinessTaskScopedTokenDTO result = service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null);

        assertThat(result).isSameAs(token);
    }

    @Test
    void canonicalPlatformPrincipalCanUseAnUpstreamPoolVisibleToClientApp() {
        stubCredentialAndToken(ResourceOwnerType.PLATFORM, BizWorkerPoolService.PLATFORM_OWNER_ID);
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1")));
        when(workerPoolMemberRepository.findByPoolIdAndWorkerId("pool-1", WORKER_ID))
                .thenReturn(Optional.of(member()));

        BusinessTaskScopedTokenDTO result = service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null);

        assertThat(result).isSameAs(token);
    }

    @Test
    void canonicalPlatformPrincipalCannotUseAnUpstreamPoolHiddenFromClientApp() {
        stubCredentialAndToken(ResourceOwnerType.PLATFORM, BizWorkerPoolService.PLATFORM_OWNER_ID);
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-2")));
        when(workerPoolMemberRepository.findByPoolIdAndWorkerId("pool-1", WORKER_ID))
                .thenReturn(Optional.of(member()));

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");
    }

    @Test
    void canonicalPlatformPrincipalRejectsPlatformPoolNotOwnedByItsTenant() {
        stubCredentialAndToken(ResourceOwnerType.PLATFORM, BizWorkerPoolService.PLATFORM_OWNER_ID);
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool(ResourceOwnerType.PLATFORM, "another-tenant")));
        when(workerPoolMemberRepository.findByPoolIdAndWorkerId("pool-1", WORKER_ID))
                .thenReturn(Optional.of(member()));

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");
    }

    @Test
    void nonCanonicalPlatformOwnerIsRejected() {
        stubCredentialAndToken(ResourceOwnerType.PLATFORM, "tenant-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool(ResourceOwnerType.PLATFORM, "tenant-1")));
        when(workerPoolMemberRepository.findByPoolIdAndWorkerId("pool-1", WORKER_ID))
                .thenReturn(Optional.of(member()));

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");
    }

    @Test
    void upstreamOwnedPhysicalRouteUsesExactClientAppOwnerWithoutPoolFallback() {
        token.setWorkerPoolId(WORKER_ID);
        stubCredentialAndToken(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        when(workerPoolRepository.findByPoolIdAndTenantId(WORKER_ID, "tenant-1"))
                .thenReturn(Optional.empty());
        when(workerPoolRepository.findByPoolId(WORKER_ID)).thenReturn(Optional.empty());

        BusinessTaskScopedTokenDTO result = service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null);

        assertThat(result).isSameAs(token);
        verifyNoInteractions(workerPoolMemberRepository);
    }

    @Test
    void physicalRouteRejectsUpstreamOwnerMismatch() {
        token.setWorkerPoolId(WORKER_ID);
        stubCredentialAndToken(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-2");
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        when(workerPoolRepository.findByPoolIdAndTenantId(WORKER_ID, "tenant-1"))
                .thenReturn(Optional.empty());
        when(workerPoolRepository.findByPoolId(WORKER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");
    }

    @Test
    void canonicalPlatformPhysicalRouteIsAccepted() {
        token.setWorkerPoolId(WORKER_ID);
        stubCredentialAndToken(
                ResourceOwnerType.PLATFORM, BizWorkerPoolService.PLATFORM_OWNER_ID);
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        when(workerPoolRepository.findByPoolIdAndTenantId(WORKER_ID, "tenant-1"))
                .thenReturn(Optional.empty());
        when(workerPoolRepository.findByPoolId(WORKER_ID)).thenReturn(Optional.empty());

        BusinessTaskScopedTokenDTO result = service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null);

        assertThat(result).isSameAs(token);
    }

    @Test
    void physicalPlatformRouteRejectsNonCanonicalIdentityOwner() {
        token.setWorkerPoolId(WORKER_ID);
        stubCredentialAndToken(ResourceOwnerType.PLATFORM, "tenant-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        when(workerPoolRepository.findByPoolIdAndTenantId(WORKER_ID, "tenant-1"))
                .thenReturn(Optional.empty());
        when(workerPoolRepository.findByPoolId(WORKER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");
    }

    @Test
    void crossTenantPoolCannotBeReinterpretedAsPhysicalRoute() {
        token.setWorkerPoolId(WORKER_ID);
        stubCredentialAndToken(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        when(workerPoolRepository.findByPoolIdAndTenantId(WORKER_ID, "tenant-1"))
                .thenReturn(Optional.empty());
        when(workerPoolRepository.findByPoolId(WORKER_ID))
                .thenReturn(Optional.of(pool(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1")));

        assertThatThrownBy(() -> service.authorize(
                TASK_TOKEN, WORKER_ID, WORKER_CREDENTIAL, LEASE_ID, null))
                .isInstanceOf(SecurityException.class)
                .hasMessage("worker principal is not authorized for task");
    }

    private void stubValidUpstreamBinding() {
        stubCredentialAndToken(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("app-1", "tenant-1"))
                .thenReturn(Optional.of(clientApp("upstream-1")));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool(ResourceOwnerType.UPSTREAM_SYSTEM, "upstream-1")));
        when(workerPoolMemberRepository.findByPoolIdAndWorkerId("pool-1", WORKER_ID))
                .thenReturn(Optional.of(member()));
    }

    private void stubCredentialAndToken(ResourceOwnerType ownerType, String ownerId) {
        when(credentialService.requireStrictCredential(WORKER_ID, WORKER_CREDENTIAL))
                .thenReturn(principal(ownerType, ownerId));
        when(taskService.resolveTaskScopedToken(TASK_TOKEN)).thenReturn(token);
    }

    private BusinessTaskScopedTokenDTO token() {
        BusinessTaskScopedTokenDTO value = new BusinessTaskScopedTokenDTO();
        value.setTenantId("tenant-1");
        value.setClientAppId("app-1");
        value.setUpstreamUserId("user-1");
        value.setTaskId("task-1");
        value.setSessionId("session-1");
        value.setSkillId("skill-1");
        value.setWorkerPoolId("pool-1");
        value.setWorkerId(WORKER_ID);
        value.setWorkerLeaseId(LEASE_ID);
        return value;
    }

    private BizWorkerPrincipal principal(ResourceOwnerType ownerType, String ownerId) {
        return BizWorkerPrincipal.builder()
                .workerId(WORKER_ID)
                .ownerType(ownerType)
                .ownerId(ownerId)
                .workerBackend("langgraph-biz-worker")
                .credentialVersion(1)
                .build();
    }

    private ClientAppEntity clientApp(String upstreamSystemId) {
        ClientAppEntity app = new ClientAppEntity();
        app.setClientAppId("app-1");
        app.setTenantId("tenant-1");
        app.setUpstreamSystemId(upstreamSystemId);
        app.setStatus(ClientAppService.STATUS_ACTIVE);
        return app;
    }

    private BizWorkerPoolEntity pool(ResourceOwnerType ownerType, String ownerId) {
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId("pool-1");
        pool.setTenantId("tenant-1");
        pool.setOwnerType(ownerType);
        pool.setOwnerId(ownerId);
        pool.setWorkerBackend("langgraph-biz-worker");
        pool.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        pool.setHealthStatus(BizWorkerPoolService.HEALTHY);
        return pool;
    }

    private BizWorkerPoolMemberEntity member() {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setPoolId("pool-1");
        member.setWorkerId(WORKER_ID);
        member.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        return member;
    }

    private static Stream<Arguments> partialOrBlankHeaders() {
        return Stream.of(
                Arguments.of(WORKER_ID, null, null),
                Arguments.of(null, WORKER_CREDENTIAL, null),
                Arguments.of(null, null, LEASE_ID),
                Arguments.of(WORKER_ID, WORKER_CREDENTIAL, null),
                Arguments.of(WORKER_ID, null, LEASE_ID),
                Arguments.of(null, WORKER_CREDENTIAL, LEASE_ID),
                Arguments.of(" ", WORKER_CREDENTIAL, LEASE_ID),
                Arguments.of(WORKER_ID, " ", LEASE_ID),
                Arguments.of(WORKER_ID, WORKER_CREDENTIAL, " "));
    }
}
