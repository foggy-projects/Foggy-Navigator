package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskTerminalStateEntity;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskTerminalStateRepository;
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
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BusinessTaskScopedTokenLifecycleService {

    private final BusinessTaskScopedTokenRepository tokenRepository;
    private final BusinessTaskTerminalStateRepository terminalStateRepository;
    private final BusinessTaskScopedTokenPolicyService tokenPolicyService;
    private final BusinessAgentTaskScopedTokenRuntimeStore tokenRuntimeStore;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BusinessTaskScopedTokenEntity issueNewToken(
            BusinessTaskScopedTokenEntity token, String plainToken) {
        return persistNewToken(token, plainToken);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BusinessTaskScopedTokenEntity issuePreboundToken(
            BusinessTaskScopedTokenEntity token,
            String plainToken,
            String workerId,
            String workerLeaseId) {
        requireText(workerId, "workerId is required for prebound token");
        requireText(workerLeaseId, "workerLeaseId is required for prebound token");
        if (token == null) {
            throw new IllegalArgumentException("token is required");
        }
        String normalizedWorkerId = workerId.trim();
        String normalizedWorkerLeaseId = workerLeaseId.trim();
        if (StringUtils.hasText(token.getWorkerId())
                && !normalizedWorkerId.equals(token.getWorkerId().trim())) {
            throw new IllegalStateException("token already targets another worker");
        }
        if (StringUtils.hasText(token.getWorkerLeaseId())
                && !normalizedWorkerLeaseId.equals(token.getWorkerLeaseId().trim())) {
            throw new IllegalStateException("token already has another worker lease");
        }
        token.setWorkerId(normalizedWorkerId);
        token.setWorkerLeaseId(normalizedWorkerLeaseId);
        return persistNewToken(token, plainToken);
    }

    private BusinessTaskScopedTokenEntity persistNewToken(
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

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = TerminalTaskBindingException.class)
    public void bindOpenApiTokenToWorkerTask(
            String tenantId,
            String plainToken,
            String workerTaskId,
            String workerSessionId) {
        bindOpenApiTokenToWorkerTask(
                tenantId, plainToken, workerTaskId, workerSessionId, null, null);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = TerminalTaskBindingException.class)
    public void bindOpenApiTokenToWorkerTask(
            String tenantId,
            String plainToken,
            String workerTaskId,
            String workerSessionId,
            String workerId,
            String workerLeaseId) {
        requireText(tenantId, "tenantId is required");
        requireText(plainToken, "plainToken is required");
        requireText(workerTaskId, "workerTaskId is required");

        BusinessTaskScopedTokenEntity token = tokenRepository
                .findByTokenHashForUpdate(SecretTokenSupport.sha256(plainToken))
                .orElseThrow(() -> new IllegalArgumentException("invalid token"));
        bindToken(token, tenantId, plainToken, workerTaskId, workerSessionId, workerId, workerLeaseId);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = TerminalTaskBindingException.class)
    public void bindIssuedTokenToWorkerTask(
            String tenantId,
            String tokenId,
            String plainToken,
            String workerTaskId,
            String workerSessionId,
            String workerId) {
        bindIssuedTokenToWorkerTask(
                tenantId, tokenId, plainToken, workerTaskId, workerSessionId, workerId, null);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = TerminalTaskBindingException.class)
    public void bindIssuedTokenToWorkerTask(
            String tenantId,
            String tokenId,
            String plainToken,
            String workerTaskId,
            String workerSessionId,
            String workerId,
            String workerLeaseId) {
        requireText(tenantId, "tenantId is required");
        requireText(tokenId, "tokenId is required");
        requireText(plainToken, "plainToken is required");
        requireText(workerTaskId, "workerTaskId is required");

        BusinessTaskScopedTokenEntity token = tokenRepository
                .findByTokenIdAndTenantIdForUpdate(tokenId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task token not found"));
        bindToken(token, tenantId, plainToken, workerTaskId, workerSessionId, workerId, workerLeaseId);
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

        return revokeTokens(
                tokenRepository.findByTaskIdAndTenantIdForUpdate(taskId, tenantId),
                revokedBy,
                reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeTaskScopedTokensForWorkerTask(
            String tenantId, String workerTaskId, String revokedBy, String reason) {
        requireText(tenantId, "tenantId is required");
        requireText(workerTaskId, "workerTaskId is required");
        requireText(reason, "reason is required");

        return revokeTokens(
                tokenRepository.findByTenantIdAndWorkerTaskIdForUpdate(tenantId, workerTaskId),
                revokedBy,
                reason);
    }

    /**
     * Writes the authorization-authoritative terminal tombstone in the
     * provider status transaction. Physical token-row revocation is
     * deliberately not performed here: a token write failure must never roll
     * back or erase the tombstone that makes Gateway authorization fail closed.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean recordTerminalState(
            String tenantId,
            String workerTaskId,
            String providerTaskUserId,
            String sourceAgentId,
            String terminalStatus) {
        requireText(tenantId, "tenantId is required");
        requireText(workerTaskId, "workerTaskId is required");
        requireText(providerTaskUserId, "providerTaskUserId is required");
        requireText(terminalStatus, "terminalStatus is required");

        String normalizedTenant = tenantId.trim();
        String normalizedWorkerTask = workerTaskId.trim();
        String normalizedProviderUser = providerTaskUserId.trim();
        // Keep every terminal path on the same lock order as bindToken:
        // task-token row(s) first, then the terminal marker.
        CapabilityCorrelation correlation = consistentCorrelation(
                tokenRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                        normalizedTenant, normalizedWorkerTask),
                normalizedTenant,
                normalizedWorkerTask);

        LocalDateTime now = LocalDateTime.now();
        BusinessTaskTerminalStateEntity terminal = terminalStateRepository
                .findByTenantIdAndWorkerTaskIdForUpdate(
                        normalizedTenant, normalizedWorkerTask)
                .orElseGet(BusinessTaskTerminalStateEntity::new);
        if (terminal.getId() == null) {
            terminal.setTenantId(normalizedTenant);
            terminal.setWorkerTaskId(normalizedWorkerTask);
            terminal.setTerminalAt(now);
        }
        if (StringUtils.hasText(terminal.getProviderTaskUserId())
                && !normalizedProviderUser.equals(terminal.getProviderTaskUserId())) {
            throw new SecurityException("worker task terminal provider owner mismatch");
        }
        terminal.setProviderTaskUserId(normalizedProviderUser);
        if (correlation != null) {
            if (StringUtils.hasText(terminal.getBusinessTaskId())
                    && !correlation.businessTaskId().equals(terminal.getBusinessTaskId())) {
                throw new IllegalStateException(
                        "worker task terminal capability task mismatch");
            }
            if (StringUtils.hasText(terminal.getNavigatorEffectiveUserId())
                    && !correlation.navigatorEffectiveUserId().equals(
                            terminal.getNavigatorEffectiveUserId())) {
                throw new SecurityException(
                        "worker task terminal capability actor mismatch");
            }
            terminal.setBusinessTaskId(correlation.businessTaskId());
            terminal.setNavigatorEffectiveUserId(
                    correlation.navigatorEffectiveUserId());
        }
        terminal.setSourceAgentId(trimToNull(sourceAgentId));
        terminal.setTerminalStatus(terminalStatus.trim().toUpperCase(Locale.ROOT));
        terminal.setExpiresAt(now.plus(tokenPolicyService.maximumCapabilityLifetime())
                .plusHours(1));
        terminalStateRepository.saveAndFlush(terminal);
        return true;
    }

    /**
     * Best-effort materialization of the durable tombstone into token rows.
     * The tombstone remains authoritative if this independent transaction
     * fails; replaying the terminal event safely retries this operation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int materializeTerminalRevocation(
            String tenantId,
            String workerTaskId,
            String revokedBy) {
        requireText(tenantId, "tenantId is required");
        requireText(workerTaskId, "workerTaskId is required");
        String normalizedTenant = tenantId.trim();
        String normalizedWorkerTask = workerTaskId.trim();

        // Use the same token -> terminal lock order as recordTerminalState and
        // bindToken so replay cannot invert row locks under concurrent bind.
        List<BusinessTaskScopedTokenEntity> candidates = tokenRepository
                .findByTenantIdAndWorkerTaskIdForUpdate(
                        normalizedTenant, normalizedWorkerTask);
        CapabilityCorrelation correlation = consistentCorrelation(
                candidates, normalizedTenant, normalizedWorkerTask);
        if (correlation == null) {
            // An event-before-bind marker remains authoritative for late bind,
            // but there is no token row to materialize or mark completed yet.
            return 0;
        }
        BusinessTaskTerminalStateEntity terminal = terminalStateRepository
                .findByTenantIdAndWorkerTaskIdForUpdate(
                        normalizedTenant, normalizedWorkerTask)
                .orElse(null);
        if (terminal == null || !terminal.getExpiresAt().isAfter(LocalDateTime.now())) {
            return 0;
        }
        if (StringUtils.hasText(terminal.getBusinessTaskId())
                && !correlation.businessTaskId().equals(terminal.getBusinessTaskId())) {
            throw new SecurityException("terminal tombstone capability correlation mismatch");
        }
        if (StringUtils.hasText(terminal.getNavigatorEffectiveUserId())
                && !correlation.navigatorEffectiveUserId().equals(
                        terminal.getNavigatorEffectiveUserId())) {
            throw new SecurityException("terminal tombstone capability correlation mismatch");
        }
        terminal.setBusinessTaskId(correlation.businessTaskId());
        terminal.setNavigatorEffectiveUserId(
                correlation.navigatorEffectiveUserId());
        int revoked = revokeTokens(
                candidates,
                revokedBy,
                "worker task reached terminal status: "
                        + terminal.getTerminalStatus());
        terminal.setRevocationCompletedAt(LocalDateTime.now());
        terminalStateRepository.save(terminal);
        return revoked;
    }

    private CapabilityCorrelation consistentCorrelation(
            List<BusinessTaskScopedTokenEntity> candidates,
            String tenantId,
            String workerTaskId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        BusinessTaskScopedTokenEntity first = candidates.get(0);
        requireText(first.getTaskId(), "persisted task token taskId is required");
        requireText(first.getNavigatorEffectiveUserId(),
                "persisted task token navigatorEffectiveUserId is required");
        String businessTaskId = first.getTaskId().trim();
        String navigatorEffectiveUserId = first.getNavigatorEffectiveUserId().trim();
        for (BusinessTaskScopedTokenEntity candidate : candidates) {
            if (candidate == null
                    || !tenantId.equals(trimToNull(candidate.getTenantId()))
                    || !workerTaskId.equals(trimToNull(candidate.getWorkerTaskId()))
                    || !businessTaskId.equals(trimToNull(candidate.getTaskId()))
                    || !navigatorEffectiveUserId.equals(
                            trimToNull(candidate.getNavigatorEffectiveUserId()))) {
                throw new SecurityException(
                        "worker task is bound to inconsistent task capabilities");
            }
        }
        return new CapabilityCorrelation(businessTaskId, navigatorEffectiveUserId);
    }

    @Transactional(readOnly = true)
    public void requireNotTerminal(BusinessTaskScopedTokenEntity token) {
        if (token == null) {
            throw new IllegalArgumentException("token is required");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean terminalBusinessTask = StringUtils.hasText(token.getTaskId())
                && terminalStateRepository.existsByTenantIdAndBusinessTaskIdAndExpiresAtAfter(
                        token.getTenantId(), token.getTaskId(), now);
        boolean terminalWorkerTask = StringUtils.hasText(token.getWorkerTaskId())
                && terminalStateRepository.existsByTenantIdAndWorkerTaskIdAndExpiresAtAfter(
                        token.getTenantId(), token.getWorkerTaskId(), now);
        if (terminalBusinessTask || terminalWorkerTask) {
            throw new IllegalStateException("task token belongs to a terminal task");
        }
    }

    private int revokeTokens(
            List<BusinessTaskScopedTokenEntity> tokens,
            String revokedBy,
            String reason) {
        List<BusinessTaskScopedTokenEntity> revoked = new ArrayList<>();
        for (BusinessTaskScopedTokenEntity token : tokens) {
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
            String workerId,
            String workerLeaseId) {
        if (!tenantId.equals(token.getTenantId())) {
            throw new SecurityException("token tenant mismatch");
        }
        if (!SecretTokenSupport.sha256(plainToken).equals(token.getTokenHash())) {
            throw new SecurityException("task token secret mismatch");
        }
        requireActive(token);
        String normalizedWorkerTaskId = workerTaskId.trim();
        if (StringUtils.hasText(token.getWorkerTaskId()) &&
                !normalizedWorkerTaskId.equals(token.getWorkerTaskId())) {
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
        String persistedWorkerId = trimToNull(token.getWorkerId());
        String persistedWorkerLeaseId = trimToNull(token.getWorkerLeaseId());
        String resolvedWorkerLeaseId = trimToNull(workerLeaseId);
        if (persistedWorkerId != null) {
            if (resolvedWorkerId == null || !resolvedWorkerId.equals(persistedWorkerId)) {
                throw new IllegalStateException("token already bound to another worker");
            }
        }
        if (persistedWorkerLeaseId != null) {
            if (resolvedWorkerLeaseId == null
                    || !resolvedWorkerLeaseId.equals(persistedWorkerLeaseId)) {
                throw new IllegalStateException("token worker lease mismatch");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        BusinessTaskTerminalStateEntity terminal = terminalStateRepository
                .findByTenantIdAndWorkerTaskIdForUpdate(
                        token.getTenantId(), normalizedWorkerTaskId)
                .filter(marker -> marker.getExpiresAt() != null
                        && marker.getExpiresAt().isAfter(now))
                .orElse(null);
        if (terminal != null) {
            persistRejectedTerminalBinding(
                    token,
                    terminal,
                    normalizedWorkerTaskId,
                    resolvedWorkerSessionId,
                    resolvedWorkerId);
            throw new TerminalTaskBindingException(
                    "cannot bind task token to a terminal worker task");
        }
        if (terminalStateRepository.existsByTenantIdAndBusinessTaskIdAndExpiresAtAfter(
                token.getTenantId(), token.getTaskId(), now)) {
            // This tombstone belongs to another worker-task tuple. Fail closed
            // without overwriting either tuple; the dedicated late-bind
            // exception is reserved for safety writes committed above.
            throw new IllegalStateException(
                    "cannot bind task token to a terminal worker task");
        }

        token.setWorkerTaskId(normalizedWorkerTaskId);
        token.setWorkerSessionId(resolvedWorkerSessionId);
        if (StringUtils.hasText(resolvedWorkerId)) {
            token.setWorkerId(resolvedWorkerId);
        }
        tokenRepository.save(token);

        afterCommit(() -> {
            tokenRuntimeStore.registerToken(
                    tenantId, token.getSessionId(), normalizedWorkerTaskId,
                    plainToken, token.getExpiresAt());
            if (!resolvedWorkerSessionId.equals(token.getSessionId())) {
                tokenRuntimeStore.registerToken(
                        tenantId, resolvedWorkerSessionId, normalizedWorkerTaskId,
                        plainToken, token.getExpiresAt());
            }
        });
    }

    private void persistRejectedTerminalBinding(
            BusinessTaskScopedTokenEntity token,
            BusinessTaskTerminalStateEntity terminal,
            String workerTaskId,
            String workerSessionId,
            String workerId) {
        requireText(token.getTaskId(), "persisted task token taskId is required");
        requireText(token.getNavigatorEffectiveUserId(),
                "persisted task token navigatorEffectiveUserId is required");
        String businessTaskId = token.getTaskId().trim();
        String capabilityActor = token.getNavigatorEffectiveUserId().trim();
        boolean businessTaskMismatch = StringUtils.hasText(terminal.getBusinessTaskId())
                && !businessTaskId.equals(terminal.getBusinessTaskId());
        boolean capabilityActorMismatch =
                StringUtils.hasText(terminal.getNavigatorEffectiveUserId())
                        && !capabilityActor.equals(
                                terminal.getNavigatorEffectiveUserId());

        token.setWorkerTaskId(workerTaskId);
        token.setWorkerSessionId(workerSessionId);
        if (StringUtils.hasText(workerId)) {
            token.setWorkerId(workerId);
        }
        markTokenRevoked(
                token,
                "system:terminal-late-bind",
                "worker task reached terminal state before token binding");
        tokenRepository.save(token);

        afterCommit(() -> removeRuntimeTokenAliases(token));
        if (businessTaskMismatch || capabilityActorMismatch) {
            // Never rewrite an established marker correlation. The exact
            // tenant + workerTask tombstone still authorizes the safety write
            // above, which must commit before this mismatch reaches callers.
            throw new TerminalTaskBindingException(
                    "terminal tombstone capability correlation mismatch");
        }

        terminal.setBusinessTaskId(businessTaskId);
        terminal.setNavigatorEffectiveUserId(capabilityActor);
        terminal.setRevocationCompletedAt(LocalDateTime.now());
        terminalStateRepository.saveAndFlush(terminal);
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

    private record CapabilityCorrelation(
            String businessTaskId,
            String navigatorEffectiveUserId) {
    }
}
