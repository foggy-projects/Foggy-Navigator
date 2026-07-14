package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.config.WorkerGatewayProperties;
import com.foggy.navigator.business.agent.model.dto.BizWorkerPrincipal;
import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Authenticates an HTTP Worker Gateway caller and binds that principal to the
 * task capability it presents.
 *
 * <p>Headerless access is retained only as an explicit internal-development
 * compatibility path. Supplying any Worker principal header opts the request
 * into strict authentication; malformed or partial header sets never fall
 * back to the compatibility path.</p>
 */
@Service
@RequiredArgsConstructor
public class WorkerGatewayRequestAuthorizationService {

    public static final String HEADER_WORKER_ID = "X-Navigator-Worker-Id";
    public static final String HEADER_WORKER_CREDENTIAL = "X-Navigator-Worker-Credential";
    public static final String HEADER_WORKER_LEASE_ID = "X-Navigator-Worker-Lease-Id";
    public static final String LEGACY_HEADER_WORKER_ID = "X-Worker-Id";

    private static final String AUTHENTICATION_REQUIRED = "worker credential is required";
    private static final String PRINCIPAL_NOT_AUTHORIZED =
            "worker principal is not authorized for task";

    private final WorkerGatewayProperties properties;
    private final BusinessAgentTaskService taskService;
    private final BusinessTaskScopedTokenPolicyService tokenPolicyService;
    private final BizWorkerCredentialService credentialService;
    private final ClientAppRepository clientAppRepository;
    private final BizWorkerPoolRepository workerPoolRepository;
    private final BizWorkerPoolMemberRepository workerPoolMemberRepository;

    public BusinessTaskScopedTokenDTO authorize(
            String taskToken,
            String workerId,
            String workerCredential,
            String workerLeaseId) {
        return authorize(taskToken, workerId, workerCredential, workerLeaseId, null);
    }

    @Transactional(readOnly = true)
    public BusinessTaskScopedTokenDTO authorize(
            String taskToken,
            String workerId,
            String workerCredential,
            String workerLeaseId,
            String legacyWorkerId) {
        if (legacyWorkerId != null) {
            throw authenticationRequired();
        }

        boolean allAbsent = workerId == null
                && workerCredential == null
                && workerLeaseId == null;
        if (allAbsent) {
            if (properties.isExternalEnabled()) {
                throw authenticationRequired();
            }
            return resolveGatewayToken(taskToken);
        }

        if (!StringUtils.hasText(workerId)
                || !StringUtils.hasText(workerCredential)
                || !StringUtils.hasText(workerLeaseId)) {
            throw authenticationRequired();
        }

        BizWorkerPrincipal principal = credentialService.requireStrictCredential(
                workerId.trim(), workerCredential);
        BusinessTaskScopedTokenDTO token = resolveGatewayToken(taskToken);
        requireStrictBinding(token, principal, workerId.trim(), workerLeaseId.trim());
        return token;
    }

    private BusinessTaskScopedTokenDTO resolveGatewayToken(String taskToken) {
        BusinessTaskScopedTokenDTO token = taskService.resolveTaskScopedToken(taskToken);
        tokenPolicyService.requireGatewayToken(token);
        return token;
    }

    private void requireStrictBinding(
            BusinessTaskScopedTokenDTO token,
            BizWorkerPrincipal principal,
            String headerWorkerId,
            String headerLeaseId) {
        if (token == null || principal == null) {
            throw principalNotAuthorized();
        }

        String tenantId = requireTokenText(token.getTenantId());
        String clientAppId = requireTokenText(token.getClientAppId());
        requireTokenText(token.getUpstreamUserId());
        String poolId = requireTokenText(token.getWorkerPoolId());
        String tokenWorkerId = requireTokenText(token.getWorkerId());
        String tokenLeaseId = requireTokenText(token.getWorkerLeaseId());

        if (!headerWorkerId.equals(principal.getWorkerId())
                || !headerWorkerId.equals(tokenWorkerId)
                || !headerLeaseId.equals(tokenLeaseId)) {
            throw principalNotAuthorized();
        }

        ClientAppEntity clientApp = clientAppRepository
                .findByClientAppIdAndTenantId(clientAppId, tenantId)
                .filter(app -> sameText(app.getClientAppId(), clientAppId))
                .filter(app -> sameText(app.getTenantId(), tenantId))
                .filter(app -> ClientAppService.STATUS_ACTIVE.equals(app.getStatus()))
                .orElseThrow(this::principalNotAuthorized);

        Optional<BizWorkerPoolEntity> pool = workerPoolRepository
                .findByPoolIdAndTenantId(poolId, tenantId);
        if (pool.isPresent()) {
            requirePoolBinding(pool.get(), principal, clientApp, tenantId, tokenWorkerId);
            return;
        }

        // A globally existing pool must never be reinterpreted as a physical
        // route merely because the task token carries another tenant.
        if (workerPoolRepository.findByPoolId(poolId).isPresent()
                || !poolId.equals(tokenWorkerId)
                || !StringUtils.hasText(principal.getWorkerBackend())) {
            throw principalNotAuthorized();
        }
        requirePhysicalOwner(principal, clientApp);
    }

    private void requirePoolBinding(
            BizWorkerPoolEntity pool,
            BizWorkerPrincipal principal,
            ClientAppEntity clientApp,
            String tenantId,
            String workerId) {
        if (!sameText(pool.getTenantId(), tenantId)
                || !BizWorkerPoolService.STATUS_ENABLED.equals(pool.getStatus())) {
            throw principalNotAuthorized();
        }
        requirePoolOwner(pool);
        workerPoolMemberRepository.findByPoolIdAndWorkerId(pool.getPoolId(), workerId)
                .filter(item -> sameText(item.getPoolId(), pool.getPoolId()))
                .filter(item -> sameText(item.getWorkerId(), workerId))
                .filter(item -> BizWorkerPoolService.STATUS_ENABLED.equals(item.getStatus()))
                .orElseThrow(this::principalNotAuthorized);
        if (!sameText(pool.getWorkerBackend(), principal.getWorkerBackend())) {
            throw principalNotAuthorized();
        }

        ResourceOwnerType ownerType = principal.getOwnerType();
        String ownerId = trimToNull(principal.getOwnerId());
        if (ownerType == ResourceOwnerType.PLATFORM) {
            if (!BizWorkerPoolService.PLATFORM_OWNER_ID.equals(ownerId)
                    || !isPoolVisibleToClientApp(pool, clientApp, tenantId)) {
                throw principalNotAuthorized();
            }
            return;
        }

        if (ownerType != ResourceOwnerType.UPSTREAM_SYSTEM
                || ownerId == null
                || !ownerId.equals(trimToNull(clientApp.getUpstreamSystemId()))
                || pool.getOwnerType() != ResourceOwnerType.UPSTREAM_SYSTEM
                || !ownerId.equals(trimToNull(pool.getOwnerId()))) {
            throw principalNotAuthorized();
        }
    }

    private boolean isPoolVisibleToClientApp(
            BizWorkerPoolEntity pool,
            ClientAppEntity clientApp,
            String tenantId) {
        String poolOwnerId = trimToNull(pool.getOwnerId());
        if (pool.getOwnerType() == ResourceOwnerType.PLATFORM) {
            // The default pool service owns PLATFORM pools by tenant, while
            // PLATFORM worker identities use the canonical shared owner.
            return poolOwnerId != null
                    && poolOwnerId.equals(tenantId);
        }
        return pool.getOwnerType() == ResourceOwnerType.UPSTREAM_SYSTEM
                && poolOwnerId != null
                && poolOwnerId.equals(trimToNull(clientApp.getUpstreamSystemId()));
    }

    private void requirePhysicalOwner(
            BizWorkerPrincipal principal,
            ClientAppEntity clientApp) {
        ResourceOwnerType ownerType = principal.getOwnerType();
        String ownerId = trimToNull(principal.getOwnerId());
        if (ownerType == ResourceOwnerType.PLATFORM) {
            if (!BizWorkerPoolService.PLATFORM_OWNER_ID.equals(ownerId)) {
                throw principalNotAuthorized();
            }
            return;
        }
        if (ownerType != ResourceOwnerType.UPSTREAM_SYSTEM
                || ownerId == null
                || !ownerId.equals(trimToNull(clientApp.getUpstreamSystemId()))) {
            throw principalNotAuthorized();
        }
    }

    private void requirePoolOwner(BizWorkerPoolEntity pool) {
        if ((pool.getOwnerType() != ResourceOwnerType.PLATFORM
                && pool.getOwnerType() != ResourceOwnerType.UPSTREAM_SYSTEM)
                || !StringUtils.hasText(pool.getOwnerId())) {
            throw principalNotAuthorized();
        }
    }

    private String requireTokenText(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw principalNotAuthorized();
        }
        return normalized;
    }

    private boolean sameText(String left, String right) {
        String normalizedLeft = trimToNull(left);
        return normalizedLeft != null && normalizedLeft.equals(trimToNull(right));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private SecurityException authenticationRequired() {
        return new SecurityException(AUTHENTICATION_REQUIRED);
    }

    private SecurityException principalNotAuthorized() {
        return new SecurityException(PRINCIPAL_NOT_AUTHORIZED);
    }
}
