package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeAccessTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeCredentialEntity;
import com.foggy.navigator.business.agent.repository.BusinessAgentTaskRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeAccessTokenRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeCredentialRepository;
import com.foggy.navigator.common.authorization.DeploymentIdentity;
import com.foggy.navigator.common.authorization.DeploymentIdentityProvider;
import com.foggy.navigator.common.authorization.DeploymentIdentitySource;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessTaskScopedCallerAuthorityServiceTest {

    private static final String INSTANCE_ID = "navi-instance-a";

    @Mock private ClientAppRuntimeCredentialRepository runtimeCredentialRepository;
    @Mock private ClientAppRuntimeAccessTokenRepository runtimeAccessTokenRepository;
    @Mock private ClientAppService clientAppService;
    @Mock private ClientAppUserGrantService userGrantService;
    @Mock private SkillRegistryService skillRegistryService;
    @Mock private BusinessAgentTaskRepository businessTaskRepository;
    @Mock private SessionTaskRepository sessionTaskRepository;

    private BusinessTaskScopedCallerAuthorityService service;
    private BusinessTaskScopedTokenEntity token;
    private ClientAppRuntimeCredentialEntity credential;
    private ClientAppRuntimeAccessTokenEntity accessToken;
    private SessionTaskEntity sessionTask;

    @BeforeEach
    void setUp() {
        DeploymentIdentityProvider identityProvider = () -> new DeploymentIdentity(
                INSTANCE_ID, "test", DeploymentIdentitySource.CONFIGURED, false);
        service = new BusinessTaskScopedCallerAuthorityService(
                identityProvider,
                runtimeCredentialRepository,
                runtimeAccessTokenRepository,
                clientAppService,
                userGrantService,
                skillRegistryService,
                businessTaskRepository,
                sessionTaskRepository);

        token = token();
        credential = credential();
        accessToken = accessToken();
        sessionTask = sessionTask("RUNNING");

        lenient().when(clientAppService.requireActiveClientApp("tenant-1", "app-1"))
                .thenReturn(new ClientAppEntity());
        lenient().when(runtimeCredentialRepository.findByCredentialId("credential-1"))
                .thenReturn(Optional.of(credential));
        lenient().when(runtimeAccessTokenRepository.findByTokenId("access-token-1"))
                .thenReturn(Optional.of(accessToken));
        lenient().when(businessTaskRepository.findByTaskIdAndTenantId("logical-task-1", "tenant-1"))
                .thenReturn(Optional.empty());
        lenient().when(sessionTaskRepository.findByTaskId("worker-task-1"))
                .thenReturn(Optional.of(sessionTask));
    }

    @Test
    void runtimeAuthorityAllowsOnlyTheCurrentExactCallerAndTaskIntersection() {
        service.requireCurrentAuthorityAndTask(token);
    }

    @Test
    void bindRuntimeAuthorityUsesServerOwnedInstanceAndCredentialReferences() {
        BusinessTaskScopedTokenEntity issued = new BusinessTaskScopedTokenEntity();

        service.bindRuntimeAuthority(issued, " credential-1 ", " access-token-1 ");

        assertThat(issued.getNavigatorInstanceId()).isEqualTo(INSTANCE_ID);
        assertThat(issued.getCallerAuthorityType())
                .isEqualTo(BusinessTaskScopedCallerAuthorityService.AUTHORITY_RUNTIME_ACCESS_TOKEN);
        assertThat(issued.getCallerCredentialId()).isEqualTo("credential-1");
        assertThat(issued.getCallerAccessTokenId()).isEqualTo("access-token-1");
    }

    @Test
    void crossInstanceReplayFailsBeforeCallerOrTaskLookup() {
        token.setNavigatorInstanceId("navi-instance-b");

        assertCallerDenied();

        verifyNoInteractions(runtimeCredentialRepository, runtimeAccessTokenRepository,
                businessTaskRepository, sessionTaskRepository);
    }

    @Test
    void callerCredentialReductionConstrainsTheNextUse() {
        credential.setStatus("REVOKED");

        assertCallerDenied();
    }

    @Test
    void revokedCallerAccessTokenConstrainsTheNextUse() {
        accessToken.setRevokedAt(LocalDateTime.now());

        assertCallerDenied();
    }

    @Test
    void expiredCallerAccessTokenConstrainsTheNextUse() {
        accessToken.setExpiresAt(LocalDateTime.now().minusSeconds(1));

        assertCallerDenied();
    }

    @Test
    void removedUpstreamUserGrantConstrainsTheNextUse() {
        doThrow(new SecurityException("grant revoked"))
                .when(userGrantService)
                .checkUpstreamUserAccess("tenant-1", "app-1", "upstream-user-1");

        assertThatThrownBy(() -> service.requireCurrentAuthorityAndTask(token))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void missingTaskFailsClosedIndependentlyOfTokenStorage() {
        when(sessionTaskRepository.findByTaskId("worker-task-1")).thenReturn(Optional.empty());

        assertTaskDenied();
    }

    @Test
    void taskOwnerMismatchFailsClosed() {
        sessionTask.setUserId("another-owner");

        assertTaskDenied();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "COMPLETED", "FAILED", "REJECTED", "TIMED_OUT", "TIMEOUT",
            "ABORTED", "CANCELLED", "CANCELED"
    })
    void authoritativeTerminalTaskRejectsOldToken(String terminalStatus) {
        sessionTask.setStatus(terminalStatus);

        assertTaskDenied();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "AWAITING_PERMISSION", "AWAITING_INPUT"})
    void pauseAndWaitingStatesRemainNonterminal(String activeStatus) {
        sessionTask.setStatus(activeStatus);

        service.requireCurrentAuthorityAndTask(token);
    }

    @Test
    void internalCallerStillRevalidatesCurrentGrantsAndTask() {
        token.setCallerAuthorityType(
                BusinessTaskScopedCallerAuthorityService.AUTHORITY_INTERNAL_USER_GRANTS);
        token.setCallerCredentialId(null);
        token.setCallerAccessTokenId(null);

        service.requireCurrentAuthorityAndTask(token);

        verifyNoInteractions(runtimeCredentialRepository, runtimeAccessTokenRepository);
    }

    private void assertCallerDenied() {
        assertThatThrownBy(() -> service.requireCurrentAuthorityAndTask(token))
                .isInstanceOf(SecurityException.class)
                .hasMessage("current caller authority does not permit task token use");
    }

    private void assertTaskDenied() {
        assertThatThrownBy(() -> service.requireCurrentAuthorityAndTask(token))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("task token task is missing or terminal");
    }

    private BusinessTaskScopedTokenEntity token() {
        BusinessTaskScopedTokenEntity entity = new BusinessTaskScopedTokenEntity();
        entity.setTaskId("logical-task-1");
        entity.setWorkerTaskId("worker-task-1");
        entity.setSessionId("context-1");
        entity.setTenantId("tenant-1");
        entity.setClientAppId("app-1");
        entity.setUpstreamUserId("upstream-user-1");
        entity.setNavigatorEffectiveUserId("navigator-user-1");
        entity.setNavigatorInstanceId(INSTANCE_ID);
        entity.setCallerAuthorityType(
                BusinessTaskScopedCallerAuthorityService.AUTHORITY_RUNTIME_ACCESS_TOKEN);
        entity.setCallerCredentialId("credential-1");
        entity.setCallerAccessTokenId("access-token-1");
        entity.setSkillId("skill-1");
        return entity;
    }

    private ClientAppRuntimeCredentialEntity credential() {
        ClientAppRuntimeCredentialEntity entity = new ClientAppRuntimeCredentialEntity();
        entity.setCredentialId("credential-1");
        entity.setTenantId("tenant-1");
        entity.setClientAppId("app-1");
        entity.setAppKey("app-key-1");
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        return entity;
    }

    private ClientAppRuntimeAccessTokenEntity accessToken() {
        ClientAppRuntimeAccessTokenEntity entity = new ClientAppRuntimeAccessTokenEntity();
        entity.setTokenId("access-token-1");
        entity.setCredentialId("credential-1");
        entity.setTenantId("tenant-1");
        entity.setClientAppId("app-1");
        entity.setAppKey("app-key-1");
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        return entity;
    }

    private SessionTaskEntity sessionTask(String status) {
        SessionTaskEntity entity = new SessionTaskEntity();
        entity.setTaskId("worker-task-1");
        entity.setTenantId("tenant-1");
        entity.setUserId("navigator-user-1");
        entity.setStatus(status);
        return entity;
    }
}
