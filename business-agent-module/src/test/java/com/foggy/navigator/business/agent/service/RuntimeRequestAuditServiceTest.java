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
                () -> assertFalse(audit.getAuditSideEffects().getTaskCreated()),
                () -> assertFalse(audit.getAuditSideEffects().getModelDispatched()),
                () -> assertTrue(audit.getStages().stream()
                        .anyMatch(stage -> "STANDARD_SCOPE_ADMITTED".equals(stage.getStage()))));
    }

    @Test
    void taskTerminationClientRequestIdIsIdempotentAndAuditable() {
        String requestId = UUID.randomUUID().toString();
        RuntimeRequestAuditService.AuditHandle first = service.beginTaskOperation(
                requestId, "task-terminate", "runtime-key", "runtime-secret",
                "agent-1", "user-1", "task-1");
        RuntimeRequestAuditService.AuditHandle replay = service.beginTaskOperation(
                requestId, "task-terminate", "runtime-key", "runtime-secret",
                "agent-1", "user-1", "task-1");
        assertEquals(first, replay);
        service.taskOperationCompleted(first, standardEvidence(
                "task-1", "CANCELLED", "REVOKED", true, 1, "TERMINATION_COMPLETED"), false, true);

        RuntimeRequestAuditDTO audit = exact(requestId);
        assertEquals("task-terminate", audit.getOperation());
        assertEquals("CANCELLED", audit.getStatus());
        assertEquals("REVOKED", audit.getTaskTokenStatus());
        assertTrue(audit.getStages().stream()
                .anyMatch(stage -> "TERMINATION_DISPATCHED".equals(stage.getStage())));
        assertTrue(audit.getStages().stream()
                .anyMatch(stage -> "TASK_TOKEN_REVOKED".equals(stage.getStage())));
        assertEquals(1, audits.size());
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
