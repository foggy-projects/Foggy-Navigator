package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BusinessTaskScopedTokenLifecycleService {

    private final BusinessTaskScopedTokenRepository tokenRepository;
    private final BusinessTaskScopedTokenPolicyService tokenPolicyService;
    private final BusinessAgentTaskScopedTokenRuntimeStore tokenRuntimeStore;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BusinessTaskScopedTokenEntity issueNewToken(
            BusinessTaskScopedTokenEntity token, String plainToken) {
        requireText(plainToken, "plainToken is required");
        if (token == null) {
            throw new IllegalArgumentException("token is required");
        }
        token.setTokenHash(SecretTokenSupport.sha256(plainToken));
        tokenPolicyService.initializeNewToken(token);
        BusinessTaskScopedTokenEntity saved = tokenRepository.save(token);
        afterCommit(() -> tokenRuntimeStore.registerToken(
                saved.getTenantId(), saved.getSessionId(), saved.getTaskId(), plainToken, saved.getExpiresAt()));
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bindOpenApiTokenToWorkerTask(
            String tenantId,
            String plainToken,
            String workerTaskId,
            String workerSessionId) {
        requireText(tenantId, "tenantId is required");
        requireText(plainToken, "plainToken is required");
        requireText(workerTaskId, "workerTaskId is required");

        BusinessTaskScopedTokenEntity token = tokenRepository
                .findByTokenHashForUpdate(SecretTokenSupport.sha256(plainToken))
                .orElseThrow(() -> new IllegalArgumentException("invalid token"));
        bindToken(token, tenantId, plainToken, workerTaskId, workerSessionId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bindIssuedTokenToWorkerTask(
            String tenantId,
            String tokenId,
            String plainToken,
            String workerTaskId,
            String workerSessionId,
            String workerId) {
        requireText(tenantId, "tenantId is required");
        requireText(tokenId, "tokenId is required");
        requireText(plainToken, "plainToken is required");
        requireText(workerTaskId, "workerTaskId is required");

        BusinessTaskScopedTokenEntity token = tokenRepository
                .findByTokenIdAndTenantIdForUpdate(tokenId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task token not found"));
        bindToken(token, tenantId, plainToken, workerTaskId, workerSessionId, workerId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeTaskScopedToken(
            String tenantId, String tokenId, String revokedBy, String reason) {
        requireText(tenantId, "tenantId is required");
        requireText(tokenId, "tokenId is required");
        requireText(reason, "reason is required");

        BusinessTaskScopedTokenEntity token = tokenRepository
                .findByTokenIdAndTenantIdForUpdate(tokenId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task token not found"));
        if (isRevoked(token)) {
            return;
        }

        markTokenRevoked(token, revokedBy, reason);
        tokenRepository.save(token);
        afterCommit(() -> removeRuntimeTokenAliases(token));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeTaskScopedTokenByPlainToken(
            String tenantId, String plainToken, String revokedBy, String reason) {
        requireText(tenantId, "tenantId is required");
        requireText(plainToken, "plainToken is required");
        requireText(reason, "reason is required");

        BusinessTaskScopedTokenEntity token = tokenRepository
                .findByTokenHashForUpdate(SecretTokenSupport.sha256(plainToken))
                .orElseThrow(() -> new IllegalArgumentException("invalid token"));
        if (!tenantId.equals(token.getTenantId())) {
            throw new SecurityException("token tenant mismatch");
        }
        if (isRevoked(token)) {
            return;
        }

        markTokenRevoked(token, revokedBy, reason);
        tokenRepository.save(token);
        afterCommit(() -> removeRuntimeTokenAliases(token));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeTaskScopedTokensForTask(
            String tenantId, String taskId, String revokedBy, String reason) {
        requireText(tenantId, "tenantId is required");
        requireText(taskId, "taskId is required");
        requireText(reason, "reason is required");

        List<BusinessTaskScopedTokenEntity> revoked = new ArrayList<>();
        for (BusinessTaskScopedTokenEntity token :
                tokenRepository.findByTaskIdAndTenantIdForUpdate(taskId, tenantId)) {
            if (!isRevoked(token)) {
                markTokenRevoked(token, revokedBy, reason);
                revoked.add(token);
            }
        }
        if (revoked.isEmpty()) {
            return 0;
        }
        tokenRepository.saveAll(revoked);
        afterCommit(() -> revoked.forEach(this::removeRuntimeTokenAliases));
        return revoked.size();
    }

    private void bindToken(
            BusinessTaskScopedTokenEntity token,
            String tenantId,
            String plainToken,
            String workerTaskId,
            String workerSessionId,
            String workerId) {
        if (!tenantId.equals(token.getTenantId())) {
            throw new SecurityException("token tenant mismatch");
        }
        if (!SecretTokenSupport.sha256(plainToken).equals(token.getTokenHash())) {
            throw new SecurityException("task token secret mismatch");
        }
        requireActive(token);
        if (StringUtils.hasText(token.getWorkerTaskId()) &&
                !workerTaskId.equals(token.getWorkerTaskId())) {
            throw new IllegalStateException("token already bound to another worker task");
        }

        String resolvedWorkerSessionId = StringUtils.hasText(workerSessionId)
                ? workerSessionId.trim()
                : StringUtils.hasText(token.getWorkerSessionId())
                        ? token.getWorkerSessionId()
                        : token.getSessionId();
        requireText(resolvedWorkerSessionId, "workerSessionId is required");
        if (StringUtils.hasText(token.getWorkerSessionId()) &&
                !resolvedWorkerSessionId.equals(token.getWorkerSessionId())) {
            throw new IllegalStateException("token already bound to another worker session");
        }
        String resolvedWorkerId = trimToNull(workerId);
        if (StringUtils.hasText(token.getWorkerId()) &&
                StringUtils.hasText(resolvedWorkerId) &&
                !resolvedWorkerId.equals(token.getWorkerId())) {
            throw new IllegalStateException("token already bound to another worker");
        }

        token.setWorkerTaskId(workerTaskId);
        token.setWorkerSessionId(resolvedWorkerSessionId);
        if (StringUtils.hasText(resolvedWorkerId)) {
            token.setWorkerId(resolvedWorkerId);
        }
        tokenRepository.save(token);

        afterCommit(() -> {
            tokenRuntimeStore.registerToken(
                    tenantId, token.getSessionId(), workerTaskId, plainToken, token.getExpiresAt());
            if (!resolvedWorkerSessionId.equals(token.getSessionId())) {
                tokenRuntimeStore.registerToken(
                        tenantId, resolvedWorkerSessionId, workerTaskId, plainToken, token.getExpiresAt());
            }
        });
    }

    private void requireActive(BusinessTaskScopedTokenEntity token) {
        if (token.getRevokedAt() != null) {
            throw new IllegalStateException("token is revoked");
        }
        if (!BusinessAgentTaskService.STATUS_ACTIVE.equals(token.getStatus())) {
            throw new IllegalStateException("token is not active");
        }
        if (token.getExpiresAt() == null || !token.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("token is expired");
        }
    }

    private boolean isRevoked(BusinessTaskScopedTokenEntity token) {
        return BusinessAgentTaskService.STATUS_REVOKED.equals(token.getStatus()) ||
                token.getRevokedAt() != null;
    }

    private void markTokenRevoked(
            BusinessTaskScopedTokenEntity token, String revokedBy, String reason) {
        token.setStatus(BusinessAgentTaskService.STATUS_REVOKED);
        token.setRevokedAt(LocalDateTime.now());
        token.setRevokedBy(trimToNull(revokedBy));
        token.setRevokeReason(reason.trim());
    }

    private void removeRuntimeTokenAliases(BusinessTaskScopedTokenEntity token) {
        tokenRuntimeStore.removeTokenIfMatches(
                token.getTenantId(), token.getSessionId(), token.getTaskId(), token.getTokenHash());
        if (StringUtils.hasText(token.getWorkerTaskId()) &&
                !token.getWorkerTaskId().equals(token.getTaskId())) {
            tokenRuntimeStore.removeTokenIfMatches(
                    token.getTenantId(), token.getSessionId(), token.getWorkerTaskId(), token.getTokenHash());
        }
        if (StringUtils.hasText(token.getWorkerTaskId()) &&
                StringUtils.hasText(token.getWorkerSessionId()) &&
                !token.getWorkerSessionId().equals(token.getSessionId())) {
            tokenRuntimeStore.removeTokenIfMatches(
                    token.getTenantId(), token.getWorkerSessionId(), token.getWorkerTaskId(), token.getTokenHash());
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
