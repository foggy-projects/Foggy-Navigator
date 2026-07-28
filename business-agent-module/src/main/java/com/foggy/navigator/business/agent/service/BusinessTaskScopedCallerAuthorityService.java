package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeAccessTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeCredentialEntity;
import com.foggy.navigator.business.agent.repository.BusinessAgentTaskRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeAccessTokenRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeCredentialRepository;
import com.foggy.navigator.common.authorization.DeploymentIdentityProvider;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Re-evaluates the server-owned caller and Task boundaries represented by a
 * task-scoped token. The token remains a narrowing projection; it is never an
 * independent source of authority.
 */
@Service
@RequiredArgsConstructor
public class BusinessTaskScopedCallerAuthorityService {

    public static final String AUTHORITY_INTERNAL_USER_GRANTS = "INTERNAL_USER_GRANTS";
    public static final String AUTHORITY_RUNTIME_ACCESS_TOKEN = "RUNTIME_ACCESS_TOKEN";

    private static final Set<String> TERMINAL_TASK_STATES = Set.of(
            "COMPLETED", "FAILED", "REJECTED", "TIMED_OUT", "TIMEOUT",
            "ABORTED", "CANCELLED", "CANCELED");
    private static final String CALLER_AUTHORITY_REJECTED =
            "current caller authority does not permit task token use";
    private static final String TASK_BOUNDARY_REJECTED =
            "task token task is missing or terminal";

    private final DeploymentIdentityProvider deploymentIdentityProvider;
    private final ClientAppRuntimeCredentialRepository runtimeCredentialRepository;
    private final ClientAppRuntimeAccessTokenRepository runtimeAccessTokenRepository;
    private final ClientAppService clientAppService;
    private final ClientAppUserGrantService userGrantService;
    private final SkillRegistryService skillRegistryService;
    private final BusinessAgentTaskRepository businessTaskRepository;
    private final SessionTaskRepository sessionTaskRepository;

    public void bindInternalAuthority(BusinessTaskScopedTokenEntity token) {
        requireToken(token);
        token.setNavigatorInstanceId(currentNavigatorInstanceId());
        token.setCallerAuthorityType(AUTHORITY_INTERNAL_USER_GRANTS);
        token.setCallerCredentialId(null);
        token.setCallerAccessTokenId(null);
    }

    public void bindRuntimeAuthority(
            BusinessTaskScopedTokenEntity token,
            String credentialId,
            String runtimeAccessTokenId) {
        requireToken(token);
        requireText(credentialId, "caller credentialId is required");
        requireText(runtimeAccessTokenId, "caller runtimeAccessTokenId is required");
        token.setNavigatorInstanceId(currentNavigatorInstanceId());
        token.setCallerAuthorityType(AUTHORITY_RUNTIME_ACCESS_TOKEN);
        token.setCallerCredentialId(credentialId.trim());
        token.setCallerAccessTokenId(runtimeAccessTokenId.trim());
    }

    @Transactional(readOnly = true)
    public void requireCurrentAuthorityAndTask(BusinessTaskScopedTokenEntity token) {
        requireToken(token);
        requireExactNavigatorInstance(token);
        requireCurrentCallerAuthority(token);
        requireCurrentTaskBoundary(token);
    }

    private void requireExactNavigatorInstance(BusinessTaskScopedTokenEntity token) {
        if (!sameText(token.getNavigatorInstanceId(), currentNavigatorInstanceId())) {
            throw callerAuthorityRejected();
        }
    }

    private void requireCurrentCallerAuthority(BusinessTaskScopedTokenEntity token) {
        requireTextOrReject(token.getTenantId());
        requireTextOrReject(token.getClientAppId());
        requireTextOrReject(token.getUpstreamUserId());
        requireTextOrReject(token.getNavigatorEffectiveUserId());
        requireTextOrReject(token.getSkillId());

        clientAppService.requireActiveClientApp(token.getTenantId(), token.getClientAppId());
        userGrantService.checkUpstreamUserAccess(
                token.getTenantId(), token.getClientAppId(), token.getUpstreamUserId());
        skillRegistryService.checkClientAppSkillAccess(
                token.getTenantId(), token.getClientAppId(), token.getSkillId());

        if (AUTHORITY_INTERNAL_USER_GRANTS.equals(token.getCallerAuthorityType())) {
            return;
        }
        if (!AUTHORITY_RUNTIME_ACCESS_TOKEN.equals(token.getCallerAuthorityType())) {
            throw callerAuthorityRejected();
        }

        String credentialId = requireTextOrReject(token.getCallerCredentialId());
        String accessTokenId = requireTextOrReject(token.getCallerAccessTokenId());
        LocalDateTime now = LocalDateTime.now();
        ClientAppRuntimeCredentialEntity credential = runtimeCredentialRepository
                .findByCredentialId(credentialId)
                .filter(item -> sameText(item.getTenantId(), token.getTenantId()))
                .filter(item -> sameText(item.getClientAppId(), token.getClientAppId()))
                .filter(item -> ClientAppService.STATUS_ACTIVE.equals(item.getStatus()))
                .filter(item -> item.getExpiresAt() == null || item.getExpiresAt().isAfter(now))
                .orElseThrow(this::callerAuthorityRejected);
        ClientAppRuntimeAccessTokenEntity accessToken = runtimeAccessTokenRepository
                .findByTokenId(accessTokenId)
                .filter(item -> sameText(item.getCredentialId(), credential.getCredentialId()))
                .filter(item -> sameText(item.getTenantId(), token.getTenantId()))
                .filter(item -> sameText(item.getClientAppId(), token.getClientAppId()))
                .filter(item -> ClientAppService.STATUS_ACTIVE.equals(item.getStatus()))
                .filter(item -> item.getRevokedAt() == null)
                .filter(item -> item.getExpiresAt() != null && item.getExpiresAt().isAfter(now))
                .orElseThrow(this::callerAuthorityRejected);
        if (!sameText(accessToken.getAppKey(), credential.getAppKey())) {
            throw callerAuthorityRejected();
        }
    }

    private void requireCurrentTaskBoundary(BusinessTaskScopedTokenEntity token) {
        BusinessAgentTaskEntity businessTask = businessTaskRepository
                .findByTaskIdAndTenantId(token.getTaskId(), token.getTenantId())
                .orElse(null);
        if (businessTask != null) {
            if (!sameText(businessTask.getClientAppId(), token.getClientAppId())
                    || !sameText(businessTask.getUpstreamUserId(), token.getUpstreamUserId())
                    || !sameText(businessTask.getNavigatorEffectiveUserId(),
                            token.getNavigatorEffectiveUserId())
                    || !sameText(businessTask.getSessionId(), token.getSessionId())
                    || isTerminal(businessTask.getStatus())) {
                throw taskBoundaryRejected();
            }
            return;
        }

        if (!StringUtils.hasText(token.getWorkerTaskId())) {
            throw taskBoundaryRejected();
        }
        SessionTaskEntity sessionTask = sessionTaskRepository
                .findByTaskId(token.getWorkerTaskId().trim())
                .orElseThrow(this::taskBoundaryRejected);
        if (!sameText(sessionTask.getTenantId(), token.getTenantId())
                || !sameText(sessionTask.getUserId(), token.getNavigatorEffectiveUserId())
                || isTerminal(sessionTask.getStatus())) {
            throw taskBoundaryRejected();
        }
    }

    private boolean isTerminal(String status) {
        return StringUtils.hasText(status)
                && TERMINAL_TASK_STATES.contains(status.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private String currentNavigatorInstanceId() {
        if (deploymentIdentityProvider == null
                || deploymentIdentityProvider.deploymentIdentity() == null) {
            throw callerAuthorityRejected();
        }
        return requireTextOrReject(
                deploymentIdentityProvider.deploymentIdentity().navigatorInstanceId());
    }

    private void requireToken(BusinessTaskScopedTokenEntity token) {
        if (token == null) {
            throw new IllegalArgumentException("token is required");
        }
    }

    private String requireTextOrReject(String value) {
        if (!StringUtils.hasText(value)) {
            throw callerAuthorityRejected();
        }
        return value.trim();
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private boolean sameText(String left, String right) {
        return StringUtils.hasText(left)
                && StringUtils.hasText(right)
                && left.trim().equals(right.trim());
    }

    private SecurityException callerAuthorityRejected() {
        return new SecurityException(CALLER_AUTHORITY_REJECTED);
    }

    private IllegalStateException taskBoundaryRejected() {
        return new IllegalStateException(TASK_BOUNDARY_REJECTED);
    }
}
