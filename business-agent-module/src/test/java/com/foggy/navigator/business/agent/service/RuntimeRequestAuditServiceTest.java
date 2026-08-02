package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.RuntimeRequestAuditDTO;
import com.foggy.navigator.business.agent.model.dto.RuntimeRequestAuditPageDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeCredentialEntity;
import com.foggy.navigator.business.agent.model.entity.RuntimeRequestAuditEntity;
import com.foggy.navigator.business.agent.model.entity.RuntimeRequestAuditStageEntity;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeCredentialRepository;
import com.foggy.navigator.business.agent.repository.RuntimeRequestAuditRepository;
import com.foggy.navigator.business.agent.repository.RuntimeRequestAuditStageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeRequestAuditServiceTest {

    private final RuntimeRequestAuditRepository auditRepository = mock(RuntimeRequestAuditRepository.class);
    private final RuntimeRequestAuditStageRepository stageRepository = mock(RuntimeRequestAuditStageRepository.class);
    private final ClientAppRuntimeCredentialRepository credentialRepository =
            mock(ClientAppRuntimeCredentialRepository.class);
    private final ClientAppRepository clientAppRepository = mock(ClientAppRepository.class);
    private final ClientAppRuntimeCredentialResolver credentialResolver =
            mock(ClientAppRuntimeCredentialResolver.class);
    private final RuntimeRequestAuditProperties properties = new RuntimeRequestAuditProperties();
    private final Map<String, RuntimeRequestAuditEntity> audits = new LinkedHashMap<>();
    private final List<RuntimeRequestAuditStageEntity> stages = new ArrayList<>();

    private RuntimeRequestAuditService service;
    private ClientAppEntity app;

    @BeforeEach
    void setUp() {
        app = new ClientAppEntity();
        app.setTenantId("tenant-1");
        app.setClientAppId("client-1");
        app.setUpstreamSystemId("world-sim");
        app.setStatus(ClientAppService.STATUS_ACTIVE);

        ClientAppRuntimeCredentialEntity credential = new ClientAppRuntimeCredentialEntity();
        credential.setCredentialId("cred-1");
        credential.setTenantId("tenant-1");
        credential.setClientAppId("client-1");
        credential.setAppKey("runtime-key");
        when(credentialRepository.findByAppKey("runtime-key")).thenReturn(Optional.of(credential));
        when(clientAppRepository.findByClientAppIdAndTenantId("client-1", "tenant-1"))
                .thenReturn(Optional.of(app));
        when(credentialResolver.resolve("runtime-key", "runtime-secret"))
                .thenReturn(Optional.of(ResolvedClientAppCredentialDTO.builder()
                        .credentialId("cred-1")
                        .tenantId("tenant-1")
                        .clientAppId("client-1")
                        .build()));

        when(auditRepository.findByClientRequestId(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(audits.get(invocation.getArgument(0))));
        when(auditRepository.findTopByTaskIdAndOperationOrderByReceivedAtDesc(
                anyString(), anyString()))
                .thenAnswer(invocation -> audits.values().stream()
                        .filter(value -> invocation.getArgument(0).equals(value.getTaskId()))
                        .filter(value -> invocation.getArgument(1).equals(value.getOperation()))
                        .max(Comparator.comparing(RuntimeRequestAuditEntity::getReceivedAt)));
        when(auditRepository.findTopByTaskIdAndOperationAndExpiresAtAfterOrderByReceivedAtDesc(
                anyString(), anyString(), any(Instant.class)))
                .thenAnswer(invocation -> audits.values().stream()
                        .filter(value -> invocation.getArgument(0).equals(value.getTaskId()))
                        .filter(value -> invocation.getArgument(1).equals(value.getOperation()))
                        .filter(value -> value.getExpiresAt().isAfter(invocation.getArgument(2)))
                        .max(Comparator.comparing(RuntimeRequestAuditEntity::getReceivedAt)));
        when(auditRepository.saveAndFlush(any(RuntimeRequestAuditEntity.class)))
                .thenAnswer(invocation -> saveAudit(invocation.getArgument(0)));
        when(auditRepository.save(any(RuntimeRequestAuditEntity.class)))
                .thenAnswer(invocation -> saveAudit(invocation.getArgument(0)));
        when(auditRepository.findByClientRequestIdAndTenantIdAndUpstreamSystemIdAndClientAppIdAndExpiresAtAfter(
                anyString(), anyString(), anyString(), anyString(), any(Instant.class)))
                .thenAnswer(invocation -> {
                    RuntimeRequestAuditEntity entity = audits.get(invocation.getArgument(0));
                    Instant now = invocation.getArgument(4);
                    if (entity == null
                            || !entity.getTenantId().equals(invocation.getArgument(1))
                            || !entity.getUpstreamSystemId().equals(invocation.getArgument(2))
                            || !entity.getClientAppId().equals(invocation.getArgument(3))
                            || !entity.getExpiresAt().isAfter(now)) {
                        return Optional.empty();
                    }
                    return Optional.of(entity);
                });
        when(auditRepository.findVisibleWindow(
                anyString(), anyString(), anyString(), any(Instant.class), any(Instant.class),
                any(Instant.class), nullable(String.class), nullable(String.class), nullable(String.class),
                any(Pageable.class)))
                .thenAnswer(invocation -> {
                    String tenant = invocation.getArgument(0);
                    String system = invocation.getArgument(1);
                    String client = invocation.getArgument(2);
                    Instant since = invocation.getArgument(3);
                    Instant until = invocation.getArgument(4);
                    Instant now = invocation.getArgument(5);
                    String operation = invocation.getArgument(6);
                    String agent = invocation.getArgument(7);
                    String user = invocation.getArgument(8);
                    Pageable pageable = invocation.getArgument(9);
                    return audits.values().stream()
                            .filter(value -> tenant.equals(value.getTenantId()))
                            .filter(value -> system.equals(value.getUpstreamSystemId()))
                            .filter(value -> client.equals(value.getClientAppId()))
                            .filter(value -> !value.getReceivedAt().isBefore(since)
                                    && !value.getReceivedAt().isAfter(until))
                            .filter(value -> value.getExpiresAt().isAfter(now))
                            .filter(value -> operation == null || operation.equals(value.getOperation()))
                            .filter(value -> agent == null || agent.equals(value.getAgentCode()))
                            .filter(value -> user == null || user.equals(value.getUpstreamUserId()))
                            .sorted(Comparator.comparing(RuntimeRequestAuditEntity::getReceivedAt).reversed())
                            .limit(pageable.getPageSize())
                            .toList();
                });
        when(auditRepository.findByExpiresAtBeforeOrderByExpiresAtAsc(any(Instant.class), any(Pageable.class)))
                .thenAnswer(invocation -> audits.values().stream()
                        .filter(value -> value.getExpiresAt().isBefore(invocation.getArgument(0)))
                        .limit(((Pageable) invocation.getArgument(1)).getPageSize())
                        .toList());
        doAnswer(invocation -> {
            ((List<RuntimeRequestAuditEntity>) invocation.getArgument(0))
                    .forEach(value -> audits.remove(value.getClientRequestId()));
            return null;
        }).when(auditRepository).deleteAll(anyList());

        when(stageRepository.save(any(RuntimeRequestAuditStageEntity.class)))
                .thenAnswer(invocation -> {
                    RuntimeRequestAuditStageEntity stage = invocation.getArgument(0);
                    stages.add(stage);
                    return stage;
                });
        when(stageRepository.findByClientRequestIdOrderByOccurredAtAscIdAsc(anyString()))
                .thenAnswer(invocation -> stages.stream()
                        .filter(stage -> stage.getClientRequestId().equals(invocation.getArgument(0)))
                        .toList());
        doAnswer(invocation -> {
            List<String> requestIds = new ArrayList<>((java.util.Collection<String>) invocation.getArgument(0));
            stages.removeIf(stage -> requestIds.contains(stage.getClientRequestId()));
            return null;
        }).when(stageRepository).deleteByClientRequestIdIn(anyCollection());

        service = new RuntimeRequestAuditService(
                auditRepository,
                stageRepository,
                credentialRepository,
                clientAppRepository,
                credentialResolver,
                properties);
    }

    @Test
    void recordsSuccessfulSafeAskIncludingRevocationAndNoDispatch() throws Exception {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle handle = service.beginRuntimeToken(
                requestId, "safe-ask", "runtime-key", "agent-1", "user-1");
        service.runtimeTokenIssued(handle);
        service.beginSafeSmoke(requestId, resolvedCredential(), "agent-1", "user-1");
        service.safeSmokeCompleted(handle, new RuntimeRequestAuditService.SafeSmokeEvidence(
                "smk_123", "COMPLETED", 0, "NO_RUNTIME_MODEL_TOOL_SURFACE",
                "SAFE_SMOKE_NO_RUNTIME", 0, "SAFE_SMOKE_EXPLICIT_EMPTY",
                true, "REVOKED", false, "SAFE_SMOKE_VERIFIED_NO_RUNTIME_DISPATCH"));

        RuntimeRequestAuditDTO audit = exact(requestId);
        assertAll(
                () -> assertTrue(audit.getTerminal()),
                () -> assertTrue(audit.getRuntimeTokenIssued()),
                () -> assertTrue(audit.getSafeSmokeRequestReceived()),
                () -> assertTrue(audit.getSyntheticEvidenceCreated()),
                () -> assertEquals("smk_123", audit.getTaskId()),
                () -> assertEquals("REVOKED", audit.getTaskTokenStatus()),
                () -> assertTrue(audit.getTaskTokenFunctionScopeEmpty()),
                () -> assertFalse(audit.getRuntimeDispatched()),
                () -> assertEquals(0, audit.getEffectiveToolCount()),
                () -> assertEquals(0, audit.getEffectiveFunctionCount()),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "SYNTHETIC_EVIDENCE_CREATED".equals(stage.getStage()))),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "TASK_TOKEN_REVOKED".equals(stage.getStage()))));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(audit);
        assertFalse(json.contains("runtime-secret"));
        assertFalse(json.contains("runtime-key"));
        assertFalse(json.toLowerCase().contains("authorization"));
        assertFalse(json.toLowerCase().contains("prompt"));
        assertFalse(json.toLowerCase().contains("stack"));
    }

    @Test
    void recordsRuntimeTokenRejectionWithoutTaskId() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle handle = service.beginRuntimeToken(
                requestId, "safe-ask", "runtime-key", "agent-1", "user-1");
        service.runtimeTokenRejected(handle, "RUNTIME_CREDENTIAL_INVALID");

        RuntimeRequestAuditDTO audit = exact(requestId);
        assertNull(audit.getTaskId());
        assertFalse(audit.getRuntimeTokenIssued());
        assertFalse(audit.getSafeSmokeRequestReceived());
        assertNull(audit.getRuntimeDispatched(), "unknown must not be folded into false");
        assertEquals("RUNTIME_CREDENTIAL_INVALID", audit.getSanitizedErrorCode());
        assertEquals("FAILED", audit.getResult());
    }

    @Test
    void distinguishesIssuedTokenBeforeSafeSmokeArrives() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle handle = service.beginRuntimeToken(
                requestId, "safe-ask", "runtime-key", "agent-1", "user-1");
        service.runtimeTokenIssued(handle);

        RuntimeRequestAuditDTO audit = exact(requestId);
        assertFalse(audit.getTerminal());
        assertTrue(audit.getRuntimeTokenIssued());
        assertFalse(audit.getSafeSmokeRequestReceived());
        assertFalse(audit.getSyntheticEvidenceCreated());
        assertNull(audit.getTaskId());
        assertNull(audit.getRuntimeDispatched(), "unknown must not be folded into false");
        assertEquals("WAITING_FOR_SAFE_SMOKE", audit.getStatus());
    }

    @Test
    void recordsSafeSmokeFailureAfterRuntimeTokenWasIssued() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle handle = service.beginRuntimeToken(
                requestId, "safe-ask", "runtime-key", "agent-1", "user-1");
        service.runtimeTokenIssued(handle);
        service.beginSafeSmoke(requestId, resolvedCredential(), "agent-1", "user-1");
        service.safeSmokeFailed(handle, "SAFE_SMOKE_TOKEN_SERVICE_UNAVAILABLE");

        RuntimeRequestAuditDTO audit = exact(requestId);
        assertTrue(audit.getRuntimeTokenIssued());
        assertTrue(audit.getSafeSmokeRequestReceived());
        assertFalse(audit.getSyntheticEvidenceCreated());
        assertTrue(audit.getTerminal());
        assertEquals("SAFE_SMOKE_TOKEN_SERVICE_UNAVAILABLE", audit.getSanitizedErrorCode());
        assertNull(audit.getTaskId());
        assertNull(audit.getRuntimeDispatched(), "failed pre-dispatch state remains explicitly unknown");
    }

    @Test
    void directSafeSmokeKeepsUnknownTokenIssuanceAsNull() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle handle = service.beginSafeSmoke(
                requestId, resolvedCredential(), "agent-1", "user-1");
        service.safeSmokeFailed(handle, "SAFE_SMOKE_REJECTED");

        RuntimeRequestAuditDTO audit = exact(requestId);
        assertFalse(audit.getRuntimeTokenRequestReceived());
        assertNull(audit.getRuntimeTokenIssued(), "unknown must not be folded into false");
        assertTrue(audit.getSafeSmokeRequestReceived());
        assertNull(audit.getRuntimeDispatched(), "unknown must not be folded into false");
    }

    @Test
    void supportsBoundedWindowFiltersAndLimit() {
        for (int i = 0; i < 3; i++) {
            service.beginSafeSmoke(
                    UUID.randomUUID().toString(), resolvedCredential(), "agent-1", "user-1");
        }
        Instant now = Instant.now();
        RuntimeRequestAuditPageDTO page = service.querySelfAudit(
                "runtime-key", "runtime-secret", null,
                now.minusSeconds(60), now.plusSeconds(1),
                "safe-ask", "agent-1", "user-1", 2);
        assertEquals(2, page.getCount());
        assertEquals(2, page.getLimit());
        verify(credentialResolver).resolve("runtime-key", "runtime-secret");
        verify(credentialResolver, never()).issueAccessToken(anyString(), anyString());

        properties.setMaxQueryWindow(Duration.ofHours(1));
        properties.setMaxLimit(1000);
        assertThrows(IllegalArgumentException.class, () -> service.querySelfAudit(
                "runtime-key", "runtime-secret", null,
                now.minus(Duration.ofMinutes(16)), now,
                null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.querySelfAudit(
                "runtime-key", "runtime-secret", null,
                now.minusSeconds(10), now,
                null, null, null, 101));
    }

    @Test
    void exactLookupIsScopeBoundAndExpiredRowsAreNotVisible() {
        IllegalArgumentException neverReceived = assertThrows(
                IllegalArgumentException.class,
                () -> exact(UUID.randomUUID().toString()));
        assertEquals("AUDIT_RECORD_EXPIRED_OR_NOT_FOUND", neverReceived.getMessage());

        String requestId = UUID.randomUUID().toString();
        service.beginSafeSmoke(requestId, resolvedCredential(), "agent-1", "user-1");
        RuntimeRequestAuditEntity entity = audits.get(requestId);
        entity.setExpiresAt(Instant.now().minusSeconds(1));

        IllegalArgumentException expired = assertThrows(IllegalArgumentException.class, () -> exact(requestId));
        assertEquals("AUDIT_RECORD_EXPIRED_OR_NOT_FOUND", expired.getMessage());

        entity.setExpiresAt(Instant.now().plusSeconds(60));
        app.setClientAppId("client-2");
        when(clientAppRepository.findByClientAppIdAndTenantId("client-1", "tenant-1"))
                .thenReturn(Optional.of(app));
        IllegalArgumentException crossScope = assertThrows(IllegalArgumentException.class, () -> exact(requestId));
        assertEquals("AUDIT_RECORD_EXPIRED_OR_NOT_FOUND", crossScope.getMessage());

        app.setClientAppId("client-1");
        app.setTenantId("tenant-2");
        when(clientAppRepository.findByClientAppIdAndTenantId("client-1", "tenant-1"))
                .thenReturn(Optional.of(app));
        IllegalArgumentException crossTenant = assertThrows(IllegalArgumentException.class, () -> exact(requestId));
        assertEquals("AUDIT_RECORD_EXPIRED_OR_NOT_FOUND", crossTenant.getMessage());

        app.setTenantId("tenant-1");
        app.setUpstreamSystemId("other-system");
        IllegalArgumentException crossSystem = assertThrows(IllegalArgumentException.class, () -> exact(requestId));
        assertEquals("AUDIT_RECORD_EXPIRED_OR_NOT_FOUND", crossSystem.getMessage());
    }

    @Test
    void scheduledCleanupPhysicallyRemovesExpiredAuditAndStagesInBoundedBatch() {
        String requestId = UUID.randomUUID().toString();
        service.beginSafeSmoke(requestId, resolvedCredential(), "agent-1", "user-1");
        audits.get(requestId).setExpiresAt(Instant.now().minusSeconds(1));
        assertFalse(stages.isEmpty());
        clearInvocations(auditRepository);

        service.cleanupExpiredAudits();

        assertFalse(audits.containsKey(requestId));
        assertTrue(stages.stream().noneMatch(stage -> requestId.equals(stage.getClientRequestId())));
        verify(auditRepository).findByExpiresAtBeforeOrderByExpiresAtAsc(any(Instant.class), any(Pageable.class));
    }

    @Test
    void defaultsToEnabledWeekLongTerminationReceiptsAndNightlyCron() throws Exception {
        Scheduled scheduled = RuntimeRequestAuditService.class
                .getMethod("cleanupExpiredAudits")
                .getAnnotation(Scheduled.class);

        assertTrue(properties.isTerminationReceiptEnabled());
        assertEquals(Duration.ofDays(7), properties.getTerminationReceiptRetention());
        assertEquals(Duration.ofHours(24), properties.getRetention());
        assertEquals("${navigator.runtime-audit.cleanup-cron:0 0 2 * * *}",
                scheduled.cron());
        assertEquals("", scheduled.fixedDelayString());
    }

    @Test
    void terminationReceiptUsesDedicatedWeekRetentionWithoutExtendingOtherAudits() {
        String terminationRequestId = UUID.randomUUID().toString();
        String safeSmokeRequestId = UUID.randomUUID().toString();

        service.beginTaskOperation(
                terminationRequestId,
                RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                "runtime-key",
                "runtime-secret",
                null,
                "user-1",
                "task-1");
        service.beginSafeSmoke(
                safeSmokeRequestId, resolvedCredential(), "agent-1", "user-1");

        RuntimeRequestAuditEntity termination = audits.get(terminationRequestId);
        RuntimeRequestAuditEntity safeSmoke = audits.get(safeSmokeRequestId);
        assertEquals(Duration.ofDays(7),
                Duration.between(termination.getReceivedAt(), termination.getExpiresAt()));
        assertEquals(Duration.ofHours(24),
                Duration.between(safeSmoke.getReceivedAt(), safeSmoke.getExpiresAt()));
    }

    @Test
    void scheduledCleanupDrainsOnlyTheConfiguredNumberOfBatches() {
        properties.setCleanupBatchSize(1);
        properties.setCleanupMaxBatches(2);
        for (int index = 0; index < 3; index++) {
            String requestId = UUID.randomUUID().toString();
            service.beginSafeSmoke(
                    requestId, resolvedCredential(), "agent-1", "user-1");
            audits.get(requestId).setExpiresAt(Instant.now().minusSeconds(1));
        }
        clearInvocations(auditRepository);

        service.cleanupExpiredAudits();

        assertEquals(1, audits.size());
        verify(auditRepository, times(2))
                .findByExpiresAtBeforeOrderByExpiresAtAsc(any(Instant.class), any(Pageable.class));
    }

    @Test
    void auditWritesDoNotTriggerPhysicalCleanup() {
        service.beginSafeSmoke(
                UUID.randomUUID().toString(), resolvedCredential(), "agent-1", "user-1");

        verify(auditRepository, never())
                .findByExpiresAtBeforeOrderByExpiresAtAsc(any(Instant.class), any(Pageable.class));
    }

    @Test
    void recordsStandardAskScopeAtAdmissionAndSeparatesReadOnlyAuditSideEffects() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle handle = service.beginAsk(
                requestId, resolvedCredential(), "agent-1", "user-1");
        RuntimeRequestAuditService.TaskEvidence admission = standardEvidence(
                null, "ADMITTED", "NOT_ISSUED", false, 0, "STANDARD_SCOPE_ADMITTED");
        service.taskAdmissionRecorded(handle, admission);
        service.taskOperationCompleted(handle, standardEvidence(
                "task-1", "RUNNING", "ACTIVE", true, 1, "STANDARD_ASK_DISPATCHED"), false, true);

        RuntimeRequestAuditDTO audit = exact(requestId);
        assertAll(
                () -> assertEquals("ask", audit.getOperation()),
                () -> assertEquals("task-1", audit.getTaskId()),
                () -> assertEquals(0, audit.getRequestedToolCount()),
                () -> assertEquals(0, audit.getEffectiveToolCount()),
                () -> assertEquals("NO_RUNTIME_MODEL_TOOL_SURFACE", audit.getToolScopeKind()),
                () -> assertEquals("REQUEST_EXPLICIT_EMPTY", audit.getToolScopeSource()),
                () -> assertEquals(0, audit.getRequestedFunctionCount()),
                () -> assertEquals(0, audit.getEffectiveFunctionCount()),
                () -> assertTrue(audit.getTaskTokenFunctionScopeEmpty()),
                () -> assertTrue(audit.getRuntimeDispatched()),
                () -> assertTrue(audit.getModelDispatched()),
                () -> assertFalse(audit.getBusinessFunctionDispatched()),
                () -> assertEquals(1, audit.getDispatchCount()),
                () -> assertFalse(audit.getAuditSideEffects().getNewTaskCreated()),
                () -> assertFalse(audit.getAuditSideEffects().getNewContextCreated()),
                () -> assertFalse(audit.getAuditSideEffects().getNewSessionCreated()),
                () -> assertFalse(audit.getAuditSideEffects().getAccessTokenIssued()),
                () -> assertFalse(audit.getAuditSideEffects().getRuntimeTokenIssued()),
                () -> assertFalse(audit.getAuditSideEffects().getTaskTokenIssued()),
                () -> assertFalse(audit.getAuditSideEffects().getTaskCreated()),
                () -> assertFalse(audit.getAuditSideEffects().getContextCreated()),
                () -> assertFalse(audit.getAuditSideEffects().getSessionCreated()),
                () -> assertFalse(audit.getAuditSideEffects().getModelDispatched()),
                () -> assertFalse(audit.getAuditSideEffects().getModelRedispatched()),
                () -> assertFalse(audit.getAuditSideEffects().getBusinessFunctionDispatched()),
                () -> assertFalse(audit.getAuditSideEffects().getRetryTriggered()),
                () -> assertFalse(audit.getAuditSideEffects().getRecoveryTriggered()),
                () -> assertFalse(audit.getAuditSideEffects().getProvisioningResourceChanged()),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "STANDARD_SCOPE_ADMITTED".equals(stage.getStage()))));
    }

    @Test
    void correlatesRuntimeTokenParentWithStandardAskAndQueriesByRequestIdAndWindow() {
        String tokenRequestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle token = service.beginRuntimeToken(
                tokenRequestId, "runtime-token", "runtime-key", "agent-1", "user-1");
        service.runtimeTokenIssued(token);
        String askRequestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle ask = service.beginAskRequest(
                askRequestId, tokenRequestId, "runtime-key", "agent-1", "user-1");
        service.authenticationCompleted(ask);
        service.taskAdmissionRecorded(ask, standardEvidence(
                null, "ADMITTED", "NOT_ISSUED", false, 0, "STANDARD_SCOPE_ADMITTED"));
        service.taskDispatchRecorded(ask, standardEvidence(
                "task-1", "RUNNING", "ACTIVE", true, 1, "STANDARD_ASK_DISPATCHED"));

        RuntimeRequestAuditDTO exact = exact(askRequestId);
        RuntimeRequestAuditPageDTO window = service.querySelfAudit(
                "runtime-key", "runtime-secret", null,
                exact.getReceivedAt().minusSeconds(1), exact.getReceivedAt().plusSeconds(1),
                "ask", "agent-1", "user-1", 20);

        assertAll(
                () -> assertEquals(tokenRequestId, exact.getParentClientRequestId()),
                () -> assertEquals(tokenRequestId, exact.getCorrelationId()),
                () -> assertTrue(exact.getRuntimeTokenRequestReceived()),
                () -> assertTrue(exact.getRuntimeTokenIssued()),
                () -> assertEquals(1, exact.getRuntimeTokenExchangeCount()),
                () -> assertTrue(exact.getStandardAskRequestReceived()),
                () -> assertFalse(exact.getTerminal()),
                () -> assertEquals(1, window.getCount()),
                () -> assertEquals(askRequestId, window.getItems().get(0).getClientRequestId()));
    }

    @Test
    void preTaskFailureRemainsQueryableWithNullTaskIdAndExplicitNotStages() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle ask = service.beginAskRequest(
                requestId, null, "runtime-key", "agent-1", "user-1");
        service.authenticationFailed(ask, "RUNTIME_ACCESS_TOKEN_INVALID");

        RuntimeRequestAuditDTO audit = exact(requestId);

        assertAll(
                () -> assertTrue(audit.getTerminal()),
                () -> assertNull(audit.getTaskId()),
                () -> assertFalse(audit.getTaskCreated()),
                () -> assertFalse(audit.getTaskTokenIssued()),
                () -> assertFalse(audit.getRuntimeDispatched()),
                () -> assertFalse(audit.getModelDispatched()),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "TASK_NOT_CREATED".equals(stage.getStage()))),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "TASK_TOKEN_NOT_ISSUED".equals(stage.getStage()))),
                () -> assertFalse(audit.getAuditSideEffects().getNewTaskCreated()),
                () -> assertFalse(audit.getAuditSideEffects().getRuntimeTokenIssued()),
                () -> assertFalse(audit.getAuditSideEffects().getModelRedispatched()),
                () -> assertFalse(audit.getAuditSideEffects().getProvisioningResourceChanged()));
    }

    @Test
    void terminalEventMakesRevocationAndTerminalStateVisibleWithoutChangingDispatchCounters() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle ask = service.beginAsk(
                requestId, resolvedCredential(), "agent-1", "user-1");
        service.taskAdmissionRecorded(ask, standardEvidence(
                null, "ADMITTED", "NOT_ISSUED", false, 0, "STANDARD_SCOPE_ADMITTED"));
        service.taskDispatchRecorded(ask, standardEvidence(
                "task-1", "RUNNING", "ACTIVE", true, 1, "STANDARD_ASK_DISPATCHED"));

        service.taskTerminalRecorded("task-1", "ABORTED", "OPERATOR_TERMINATED");
        RuntimeRequestAuditDTO audit = exact(requestId);

        assertAll(
                () -> assertTrue(audit.getTerminal()),
                () -> assertEquals("ABORTED", audit.getStatus()),
                () -> assertEquals("REVOKED", audit.getTaskTokenStatus()),
                () -> assertEquals(1, audit.getDispatchCount()),
                () -> assertEquals(0, audit.getRetryCount()),
                () -> assertEquals(0, audit.getRecoveryCount()),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "TASK_TERMINAL".equals(stage.getStage()))),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "TASK_TOKEN_REVOKED".equals(stage.getStage()))));
    }

    @Test
    void definitivePreProviderFailureCorrectsOptimisticAskDispatchProjection() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle ask = service.beginAsk(
                requestId, resolvedCredential(), "agent-1", "user-1");
        service.taskAdmissionRecorded(ask, standardEvidence(
                null, "ADMITTED", "NOT_ISSUED", false, 0,
                "STANDARD_SCOPE_ADMITTED"));
        service.taskDispatchRecorded(ask, standardEvidence(
                "task-1", "RUNNING", "ACTIVE", true, 1,
                "STANDARD_ASK_DISPATCHED"));

        service.taskTerminalRecorded(
                "task-1",
                "FAILED",
                "LIFECYCLE_ACTIVATION_ADMISSION_BINDING_MISMATCH",
                false,
                false,
                0);
        RuntimeRequestAuditDTO audit = exact(requestId);

        assertAll(
                () -> assertTrue(audit.getTerminal()),
                () -> assertEquals("FAILED", audit.getStatus()),
                () -> assertEquals(
                        "LIFECYCLE_ACTIVATION_ADMISSION_BINDING_MISMATCH",
                        audit.getSanitizedErrorCode()),
                () -> assertEquals("REVOKED", audit.getTaskTokenStatus()),
                () -> assertFalse(audit.getRuntimeDispatched()),
                () -> assertFalse(audit.getModelDispatched()),
                () -> assertEquals(0, audit.getDispatchCount()),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "RUNTIME_NOT_DISPATCHED"
                                .equals(stage.getStage()))),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "MODEL_NOT_DISPATCHED"
                                .equals(stage.getStage()))));
    }

    @Test
    void taskTerminationClientRequestIdIsIdempotentAndAuditable() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.TaskOperationRegistration first =
                service.beginTaskOperationIdempotent(
                requestId, "task-terminate", "runtime-key", "runtime-secret",
                "agent-1", "user-1", "task-1");
        RuntimeRequestAuditService.TaskOperationRegistration replay =
                service.beginTaskOperationIdempotent(
                requestId, "task-terminate", "runtime-key", "runtime-secret",
                "agent-1", "user-1", "task-1");
        assertFalse(first.existing());
        assertTrue(replay.existing());
        assertEquals(first.handle(), replay.handle());
        IllegalArgumentException taskMismatch = assertThrows(
                IllegalArgumentException.class,
                () -> service.beginTaskOperationIdempotent(
                        requestId, "task-terminate", "runtime-key", "runtime-secret",
                        "agent-1", "user-1", "task-2"));
        assertEquals("CLIENT_REQUEST_ID_OPERATION_MISMATCH", taskMismatch.getMessage());
        IllegalArgumentException operationMismatch = assertThrows(
                IllegalArgumentException.class,
                () -> service.beginTaskOperationIdempotent(
                        requestId, "task-reconcile", "runtime-key", "runtime-secret",
                        "agent-1", "user-1", "task-1"));
        assertEquals("CLIENT_REQUEST_ID_OPERATION_MISMATCH", operationMismatch.getMessage());
        service.taskOperationCompleted(first.handle(), standardEvidence(
                "task-1", "CANCELLED", "REVOKED", true, 1, "TERMINATION_REQUESTED"), false, true);

        RuntimeRequestAuditDTO audit = exact(requestId);
        RuntimeRequestAuditService.TaskOperationSnapshot snapshot =
                service.findSelfTaskOperation("runtime-key", "runtime-secret", requestId)
                        .orElseThrow();
        assertEquals("task-terminate", audit.getOperation());
        assertEquals("CANCELLED", audit.getStatus());
        assertEquals("REVOKED", audit.getTaskTokenStatus());
        assertTrue(snapshot.completed());
        assertEquals("TERMINATION_REQUESTED", snapshot.result());
        assertEquals("task-1", snapshot.taskId());
        assertEquals("user-1", snapshot.upstreamUserId());
        assertEquals("worker-1", snapshot.physicalWorkerId());
        assertTrue(audit.getStages().stream()
                .anyMatch(stage -> "TERMINATION_DISPATCHED".equals(stage.getStage())));
        assertTrue(audit.getStages().stream()
                .anyMatch(stage -> "TASK_TOKEN_REVOKED".equals(stage.getStage())));
        assertEquals(1, audits.size());
    }

    @Test
    void canonicalTerminalEventBackfillsTerminationReceiptAndRevocationEvidence() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle termination = service.beginTaskOperation(
                requestId, "task-terminate", "runtime-key", "runtime-secret",
                "agent-1", "user-1", "task-1");
        service.taskOperationCompleted(termination, standardEvidence(
                "task-1", "CANCEL_REQUESTED", "ACTIVE", true, 1,
                "TERMINATION_REQUESTED"), false, true);

        service.taskTerminalRecorded("task-1", "ABORTED", "OPERATOR_TERMINATED");

        RuntimeRequestAuditDTO audit = exact(requestId);
        RuntimeRequestAuditService.TaskOperationSnapshot snapshot =
                service.findSelfTaskOperation("runtime-key", "runtime-secret", requestId)
                        .orElseThrow();
        assertAll(
                () -> assertTrue(snapshot.completed()),
                () -> assertEquals("TASK_TERMINATED", snapshot.result()),
                () -> assertEquals("ABORTED", snapshot.status()),
                () -> assertEquals("REVOKED", audit.getTaskTokenStatus()),
                () -> assertEquals(1, audit.getDispatchCount()),
                () -> assertEquals(0, audit.getRetryCount()),
                () -> assertEquals(0, audit.getRecoveryCount()),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "TERMINATION_EVIDENCE_OBSERVED"
                                .equals(stage.getStage()))),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "TASK_TOKEN_REVOKED".equals(stage.getStage()))));
    }

    @Test
    void terminationSnapshotMarksAcceptedReceiptPastConvergenceDeadline() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle termination = service.beginTaskOperation(
                requestId, "task-terminate", "runtime-key", "runtime-secret",
                "agent-1", "user-1", "task-1");
        service.taskOperationCompleted(termination, standardEvidence(
                "task-1", "CANCEL_REQUESTED", "ACTIVE", true, 1,
                "TERMINATION_REQUESTED"), false, true);
        audits.get(requestId).setCompletedAt(Instant.now().minus(Duration.ofMinutes(6)));

        RuntimeRequestAuditService.TaskOperationSnapshot snapshot =
                service.findSelfTaskOperation("runtime-key", "runtime-secret", requestId)
                        .orElseThrow();

        assertTrue(snapshot.convergenceTimedOut());
        assertEquals("TERMINATION_REQUESTED", snapshot.result());
    }

    @Test
    void failedReconciliationReplayConvergesSameAuditToSuccessfulTerminalEvidence() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle handle = service.beginTaskOperation(
                requestId, "task-reconcile", "runtime-key", "runtime-secret",
                "agent-1", "user-1", "task-1");
        service.taskOperationFailed(handle, "RUNTIME_TASK_RECONCILE_EVIDENCE_UNREACHABLE");

        service.refreshCompletedTaskOperation(
                "task-1", "task-reconcile", standardEvidence(
                        "task-1", "CANCELLED", "REVOKED", true, 1,
                        "RECONCILIATION_CHANGED"));

        RuntimeRequestAuditDTO audit = exact(requestId);
        assertAll(
                () -> assertTrue(audit.getTerminal()),
                () -> assertEquals("CANCELLED", audit.getStatus()),
                () -> assertEquals("RECONCILIATION_CHANGED", audit.getResult()),
                () -> assertEquals("OPERATOR_TERMINATED", audit.getSanitizedErrorCode()),
                () -> assertNull(audit.getSafeErrorSummary()),
                () -> assertEquals("REVOKED", audit.getTaskTokenStatus()),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "REQUEST_FAILED".equals(stage.getStage()))),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "RECONCILIATION_EVIDENCE_OBSERVED".equals(stage.getStage()))),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "TASK_TOKEN_REVOKED".equals(stage.getStage()))),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "REQUEST_COMPLETED".equals(stage.getStage()))));
    }

    @Test
    void exactReceiptRefreshNeverSelectsLatestSameTaskReceipt() {
        String firstRequest = UUID.randomUUID().toString();
        String secondRequest = UUID.randomUUID().toString();
        var first = service.beginTaskOperation(
                firstRequest, "task-terminate", "runtime-key",
                "runtime-secret", "agent-1", "user-1", "task-1");
        service.taskOperationFailed(first, "FIRST_FAILURE");
        var second = service.beginTaskOperation(
                secondRequest, "task-terminate", "runtime-key",
                "runtime-secret", "agent-1", "user-1", "task-1");
        service.taskOperationFailed(second, "SECOND_FAILURE");

        service.refreshCompletedTaskOperation(
                firstRequest, "task-1", "task-terminate",
                standardEvidence("task-1", "CANCELLED", "REVOKED",
                        true, 1, "FIRST_EXACTLY_REFRESHED"));

        assertEquals("FIRST_EXACTLY_REFRESHED",
                audits.get(firstRequest).getResult());
        assertEquals("FAILED", audits.get(secondRequest).getResult());
        assertTrue(service.hasDurableTaskOperationReceipt(
                firstRequest, "task-1", "task-terminate"));
        assertFalse(service.hasDurableTaskOperationReceipt(
                firstRequest, "other-task", "task-terminate"));
    }

    private RuntimeRequestAuditService.TaskEvidence standardEvidence(
            String taskId, String status, String tokenStatus, boolean dispatched,
            int dispatchCount, String result) {
        return new RuntimeRequestAuditService.TaskEvidence(
                taskId, status, "CANCELLED".equals(status),
                "CANCELLED".equals(status) ? "OPERATOR_TERMINATED" : null,
                "agent-1", "user-1", "worker-1", "model-1", "codex-luna:high",
                0, 0, "NO_RUNTIME_MODEL_TOOL_SURFACE", "REQUEST_EXPLICIT_EMPTY",
                0, 0, "REQUEST_EXPLICIT_EMPTY", true, tokenStatus,
                dispatched, dispatched, false, dispatchCount, 0, 0, result);
    }

    private RuntimeRequestAuditDTO exact(String requestId) {
        return service.querySelfAudit(
                        "runtime-key", "runtime-secret", requestId,
                        null, null, null, null, null, null)
                .getItems().get(0);
    }

    private RuntimeRequestAuditEntity saveAudit(RuntimeRequestAuditEntity entity) {
        audits.put(entity.getClientRequestId(), entity);
        return entity;
    }

    private ResolvedClientAppCredentialDTO resolvedCredential() {
        return ResolvedClientAppCredentialDTO.builder()
                .credentialId("cred-1")
                .tenantId("tenant-1")
                .clientAppId("client-1")
                .build();
    }
}
