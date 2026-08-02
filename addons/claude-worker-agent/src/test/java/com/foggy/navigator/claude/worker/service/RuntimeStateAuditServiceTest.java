package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentSessionEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskTerminalStateEntity;
import com.foggy.navigator.business.agent.repository.BusinessAgentSessionRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentTaskRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskTerminalStateRepository;
import com.foggy.navigator.business.agent.service.A2AgentResourceResolver;
import com.foggy.navigator.business.agent.service.ClientAppRuntimeCredentialResolver;
import com.foggy.navigator.claude.worker.model.dto.RuntimeBindingAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.ErrorDiagnosticEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.session.repository.ErrorDiagnosticRepository;
import com.foggy.navigator.session.repository.TerminationOperationRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeStateAuditServiceTest {

    private final ClientAppRuntimeCredentialResolver credentialResolver =
            mock(ClientAppRuntimeCredentialResolver.class);
    private final A2AgentResourceResolver resourceResolver = mock(A2AgentResourceResolver.class);
    private final BusinessCodingAgentRepository agentRepository = mock(BusinessCodingAgentRepository.class);
    private final WorkingDirectoryRepository directoryRepository = mock(WorkingDirectoryRepository.class);
    private final LlmModelManager llmModelManager = mock(LlmModelManager.class);
    private final ClaudeWorkerRepository workerRepository = mock(ClaudeWorkerRepository.class);
    private final SessionTaskRepository sessionTaskRepository = mock(SessionTaskRepository.class);
    private final BusinessTaskTerminalStateRepository terminalStateRepository =
            mock(BusinessTaskTerminalStateRepository.class);
    private final BusinessTaskScopedTokenRepository taskTokenRepository =
            mock(BusinessTaskScopedTokenRepository.class);
    private final BusinessAgentTaskRepository businessTaskRepository =
            mock(BusinessAgentTaskRepository.class);
    private final BusinessAgentSessionRepository businessSessionRepository =
            mock(BusinessAgentSessionRepository.class);
    private final ErrorDiagnosticRepository errorDiagnosticRepository =
            mock(ErrorDiagnosticRepository.class);
    private final TerminationOperationRepository terminationOperationRepository =
            mock(TerminationOperationRepository.class);

    private RuntimeStateAuditService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeStateAuditService(
                credentialResolver,
                resourceResolver,
                agentRepository,
                directoryRepository,
                llmModelManager,
                workerRepository,
                sessionTaskRepository,
                terminalStateRepository,
                taskTokenRepository,
                businessTaskRepository,
                businessSessionRepository,
                errorDiagnosticRepository,
                terminationOperationRepository,
                new ObjectMapper());
        when(credentialResolver.resolve("runtime-key", "runtime-secret"))
                .thenReturn(Optional.of(owner()));
    }

    @Test
    void bindingAuditReturnsDurableRegistrationAndNeverIssuesTokensOrWrites() {
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setAgentId("agent-observed");
        agent.setEnabled(true);
        when(agentRepository.findByAgentIdAndTenantId("agent-observed", "tenant-a"))
                .thenReturn(Optional.of(agent));

        A2AgentResourceResolver.ResolvedAgentResource resolvedAgent =
                new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent-observed",
                        ResourceOwnerType.CLIENT_APP,
                        "app-a",
                        "app-a",
                        "skill-a",
                        "pool-observed",
                        ResourceOwnerType.CLIENT_APP,
                        null,
                        "WORKER_POOL",
                        "OPENAI_CODEX",
                        null,
                        null,
                        null,
                        null,
                        "model-observed",
                        "ignored-default",
                        "directory-observed",
                        "AGENT:CLIENT_APP");
        A2AgentResourceResolver.ResolvedModelResource resolvedModel =
                new A2AgentResourceResolver.ResolvedModelResource(
                        "model-observed",
                        "model-observed",
                        null,
                        LlmModelCategory.GENERAL,
                        "codex-luna:high",
                        "MODEL_CONFIG_DEFAULT",
                        "OPENAI_CODEX",
                        "AGENT_MODEL_BINDING");
        A2AgentResourceResolver.ResolvedWorkspaceResource resolvedWorkspace =
                new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "directory-observed",
                        "worker-observed",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.MANAGED,
                        "must-never-be-returned",
                        java.util.List.of("must-never-be-returned"),
                        false,
                        null,
                        null,
                        null,
                        "AGENT_WORKSPACE_BINDING");
        when(resourceResolver.resolveRequiredAgent(
                "tenant-a", "app-a", "user-a", "agent-observed")).thenReturn(resolvedAgent);
        when(resourceResolver.resolveRequiredModelForAgent(
                "tenant-a", "app-a", resolvedAgent, "model-observed", null, LlmModelCategory.GENERAL))
                .thenReturn(resolvedModel);
        when(resourceResolver.resolveRequiredWorkspaceForAgent(
                "tenant-a", "app-a", "user-a", resolvedAgent, "directory-observed"))
                .thenReturn(resolvedWorkspace);

        WorkingDirectoryEntity directory = new WorkingDirectoryEntity();
        directory.setDirectoryId("directory-observed");
        directory.setEnabled(true);
        when(directoryRepository.findByDirectoryId("directory-observed"))
                .thenReturn(Optional.of(directory));
        LlmModelConfigDTO model = new LlmModelConfigDTO();
        model.setId("model-observed");
        when(llmModelManager.getModelConfig("model-observed")).thenReturn(Optional.of(model));
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId("worker-observed");
        worker.setName("host-observed Claude Code Worker");
        worker.setStatus("ONLINE");
        worker.setBaseUrl("http://127.0.0.1:3131");
        worker.setCodexConfig(CodexConfig.builder()
                .baseUrl("http://127.0.0.1:3151")
                .authToken("must-never-be-returned")
                .model("must-never-be-returned")
                .build());
        when(workerRepository.findByWorkerId("worker-observed")).thenReturn(Optional.of(worker));
        when(sessionTaskRepository.countByTenantIdAndWorkerIdAndStatusIn(
                eq("tenant-a"), eq("worker-observed"), any())).thenReturn(3L);

        RuntimeBindingAuditDTO audit = service.auditBinding(
                "runtime-key",
                "runtime-secret",
                "agent-observed",
                "user-a",
                "model-observed",
                "directory-observed");

        assertEquals("host-observed", audit.getWorkerHost());
        assertEquals("codex-luna:high", audit.getModelVariant());
        assertEquals(3131, audit.getDirectoryRolePort());
        assertEquals(3151, audit.getCodexRolePort());
        assertEquals(3L, audit.getActiveTaskCount());
        assertFalse(audit.getAuditAccessTokenIssued());
        assertFalse(audit.getAuditRuntimeTokenIssued());
        assertFalse(audit.getAuditTaskTokenIssued());
        assertFalse(audit.getTaskCreated());
        assertFalse(audit.getModelDispatched());
        verify(credentialResolver, never()).issueAccessToken(any(), any());
        verify(sessionTaskRepository, never()).save(any());
        verify(agentRepository, never()).save(any());
        verify(directoryRepository, never()).save(any());
        verify(workerRepository, never()).save(any());
    }

    @Test
    void bindingAuditRejectsConflictingPinnedPhysicalWorker() {
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setAgentId("agent-observed");
        agent.setEnabled(true);
        when(agentRepository.findByAgentIdAndTenantId("agent-observed", "tenant-a"))
                .thenReturn(Optional.of(agent));

        A2AgentResourceResolver.ResolvedAgentResource resolvedAgent =
                new A2AgentResourceResolver.ResolvedAgentResource(
                        "agent-observed",
                        ResourceOwnerType.CLIENT_APP,
                        "app-a",
                        "app-a",
                        "skill-a",
                        null,
                        null,
                        null,
                        null,
                        "OPENAI_CODEX",
                        "different-worker",
                        ResourceOwnerType.CLIENT_APP,
                        "app-a",
                        "PINNED_WORKER",
                        "model-observed",
                        "ignored-default",
                        "directory-observed",
                        "AGENT:CLIENT_APP");
        A2AgentResourceResolver.ResolvedModelResource resolvedModel =
                new A2AgentResourceResolver.ResolvedModelResource(
                        "model-observed",
                        "model-observed",
                        null,
                        LlmModelCategory.GENERAL,
                        "codex-luna:high",
                        "MODEL_CONFIG_DEFAULT",
                        "OPENAI_CODEX",
                        "AGENT_MODEL_BINDING");
        A2AgentResourceResolver.ResolvedWorkspaceResource resolvedWorkspace =
                new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "directory-observed",
                        "worker-observed",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.MANAGED,
                        "must-never-be-returned",
                        java.util.List.of("must-never-be-returned"),
                        false,
                        null,
                        null,
                        null,
                        "AGENT_WORKSPACE_BINDING");
        when(resourceResolver.resolveRequiredAgent(
                "tenant-a", "app-a", "user-a", "agent-observed")).thenReturn(resolvedAgent);
        when(resourceResolver.resolveRequiredModelForAgent(
                "tenant-a", "app-a", resolvedAgent, "model-observed", null, LlmModelCategory.GENERAL))
                .thenReturn(resolvedModel);
        when(resourceResolver.resolveRequiredWorkspaceForAgent(
                "tenant-a", "app-a", "user-a", resolvedAgent, "directory-observed"))
                .thenReturn(resolvedWorkspace);

        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> service.auditBinding(
                        "runtime-key",
                        "runtime-secret",
                        "agent-observed",
                        "user-a",
                        "model-observed",
                        "directory-observed"));

        assertEquals("RUNTIME_BINDING_AUDIT_WORKER_MISMATCH", mismatch.getMessage());
        verify(workerRepository, never()).findByWorkerId(any());
    }

    @Test
    void taskAuditReturnsExistingTerminalTaskAndRevokedToken() {
        LocalDateTime created = LocalDateTime.of(2026, 7, 23, 15, 43, 22);
        LocalDateTime completed = LocalDateTime.of(2026, 7, 23, 17, 26, 55);
        SessionTaskEntity task = task(created, completed);
        when(sessionTaskRepository.findByTaskId("task-existing")).thenReturn(Optional.of(task));

        BusinessTaskTerminalStateEntity terminal = new BusinessTaskTerminalStateEntity();
        terminal.setTenantId("tenant-a");
        terminal.setWorkerTaskId("task-existing");
        terminal.setBusinessTaskId("business-task");
        terminal.setTerminalStatus("FAILED");
        terminal.setTerminalAt(completed);
        terminal.setRevocationCompletedAt(completed);
        when(terminalStateRepository.findByTenantIdAndWorkerTaskId("tenant-a", "task-existing"))
                .thenReturn(Optional.of(terminal));

        BusinessTaskScopedTokenEntity token = token("REVOKED", created, completed);
        when(taskTokenRepository.findFirstByTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
                "business-task", "tenant-a", "app-a")).thenReturn(Optional.of(token));

        ErrorDiagnosticEntity diagnostic = new ErrorDiagnosticEntity();
        diagnostic.setErrorCode("CODEX_WORKER_REMOTE_ERROR");
        when(errorDiagnosticRepository.findFirstByTaskIdAndTenantIdOrderByOccurredAtDesc(
                "task-existing", "tenant-a")).thenReturn(Optional.of(diagnostic));

        RuntimeTaskAuditDTO audit =
                service.auditTask("runtime-key", "runtime-secret", "user-a", "task-existing");

        assertTrue(audit.getTerminal());
        assertEquals("FAILED", audit.getStatus());
        assertEquals("CODEX_WORKER_REMOTE_ERROR", audit.getSanitizedErrorCode());
        assertEquals("REVOKED", audit.getTaskTokenStatus());
        assertFalse(audit.getActiveTaskRegistrationPresent());
        assertEquals(1, audit.getDispatchCount());
        assertEquals(0, audit.getRetryCount());
        assertEquals(0, audit.getRecoveryCount());
        assertEquals("worker-observed", audit.getPhysicalWorkerId());
        assertEquals("model-observed", audit.getModelConfigId());
        assertEquals("codex-luna:high", audit.getModelVariant());
        assertEquals(completed.atZone(IdGenerator.TASK_ID_DATE_ZONE).toOffsetDateTime(), audit.getCompletedAt());
        assertTrue(audit.getTerminalStages().size() >= 6);
        assertNotNull(audit.getTaskFacts());
        assertNotNull(audit.getAuditSideEffects());
        assertFalse(audit.getAuditAccessTokenIssued());
        assertFalse(audit.getAuditRuntimeTokenIssued());
        assertFalse(audit.getAuditTaskTokenIssued());
        assertFalse(audit.getTaskCreated());
        assertFalse(audit.getContextCreated());
        assertFalse(audit.getSessionCreated());
        assertFalse(audit.getModelDispatched());
        assertFalse(audit.getBusinessFunctionDispatched());
        assertFalse(audit.getRecoveryTriggered());
        verify(credentialResolver, never()).issueAccessToken(any(), any());
        verify(sessionTaskRepository, never()).save(any());
        verify(terminalStateRepository, never()).save(any());
        verify(taskTokenRepository, never()).save(any());
    }

    @Test
    void taskAuditUsesOnlyStableTaskErrorCodeWhenDiagnosticIsAbsent() {
        LocalDateTime created = LocalDateTime.of(2026, 7, 26, 10, 0, 40);
        LocalDateTime completed = LocalDateTime.of(2026, 7, 26, 10, 0, 41);
        SessionTaskEntity task = task(created, completed);
        task.setProviderTaskId(null);
        task.setErrorMessage("CODEX_WORKING_DIRECTORY_UNAVAILABLE");
        when(sessionTaskRepository.findByTaskId("task-existing")).thenReturn(Optional.of(task));
        when(taskTokenRepository.findFirstByWorkerTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
                "task-existing", "tenant-a", "app-a"))
                .thenReturn(Optional.of(token("REVOKED", created, completed)));

        RuntimeTaskAuditDTO audit =
                service.auditTask("runtime-key", "runtime-secret", "user-a", "task-existing");

        assertTrue(audit.getTerminal());
        assertEquals("FAILED", audit.getStatus());
        assertEquals("CODEX_WORKING_DIRECTORY_UNAVAILABLE", audit.getSanitizedErrorCode());
        assertEquals("REVOKED", audit.getTaskTokenStatus());
        assertEquals(0, audit.getDispatchCount());
    }

    @Test
    void lifecycleAdmissionFailureOverridesStaleOptimisticDispatchMetadata() {
        LocalDateTime created = LocalDateTime.of(2026, 8, 2, 12, 0, 0);
        LocalDateTime completed = created.plusSeconds(1);
        SessionTaskEntity task = task(created, completed);
        task.setProviderTaskId(null);
        task.setErrorMessage(
                "LIFECYCLE_ACTIVATION_ADMISSION_BINDING_MISMATCH");
        task.setTaskStateJson("{\"runtimeDispatched\":true,"
                + "\"modelDispatched\":true}");
        when(sessionTaskRepository.findByTaskId("task-existing"))
                .thenReturn(Optional.of(task));
        when(taskTokenRepository
                .findFirstByWorkerTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
                        "task-existing", "tenant-a", "app-a"))
                .thenReturn(Optional.of(token("REVOKED", created, completed)));

        RuntimeTaskAuditDTO audit = service.auditTask(
                "runtime-key", "runtime-secret", "user-a", "task-existing");

        assertEquals(
                "LIFECYCLE_ACTIVATION_ADMISSION_BINDING_MISMATCH",
                audit.getSanitizedErrorCode());
        assertEquals(0, audit.getDispatchCount());
        assertFalse(audit.getRuntimeDispatched());
        assertFalse(audit.getTaskModelDispatched());
    }

    @Test
    void taskAuditDoesNotPromoteFreeFormTaskErrorToSanitizedCode() {
        LocalDateTime created = LocalDateTime.of(2026, 7, 26, 10, 1, 40);
        SessionTaskEntity task = task(created, created.plusSeconds(1));
        task.setErrorMessage("failed in /private/workspace with token-like-value");
        when(sessionTaskRepository.findByTaskId("task-existing")).thenReturn(Optional.of(task));
        when(taskTokenRepository.findFirstByWorkerTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
                "task-existing", "tenant-a", "app-a"))
                .thenReturn(Optional.of(token("REVOKED", created, created.plusSeconds(1))));

        RuntimeTaskAuditDTO audit =
                service.auditTask("runtime-key", "runtime-secret", "user-a", "task-existing");

        assertNull(audit.getSanitizedErrorCode());
    }

    @Test
    void tokenMissingIsNotMisreportedAsRevoked() {
        SessionTaskEntity task = task(LocalDateTime.now().minusHours(1), LocalDateTime.now());
        when(sessionTaskRepository.findByTaskId("task-existing")).thenReturn(Optional.of(task));
        when(terminalStateRepository.findByTenantIdAndWorkerTaskId("tenant-a", "task-existing"))
                .thenReturn(Optional.empty());
        when(taskTokenRepository.findFirstByWorkerTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
                "task-existing", "tenant-a", "app-a")).thenReturn(Optional.empty());
        BusinessAgentSessionEntity session = new BusinessAgentSessionEntity();
        when(businessSessionRepository.findByTenantIdAndClientAppIdAndUpstreamUserIdAndSessionId(
                "tenant-a", "app-a", "user-a", "session-a")).thenReturn(Optional.of(session));

        RuntimeTaskAuditDTO audit =
                service.auditTask("runtime-key", "runtime-secret", "user-a", "task-existing");

        assertEquals(RuntimeStateAuditService.TOKEN_NOT_FOUND, audit.getTaskTokenStatus());
        assertFalse(audit.getActiveTaskRegistrationPresent());
    }

    @Test
    void unknownTokenStatusIsUnconfirmed() {
        SessionTaskEntity task = task(LocalDateTime.now().minusHours(1), LocalDateTime.now());
        when(sessionTaskRepository.findByTaskId("task-existing")).thenReturn(Optional.of(task));
        when(terminalStateRepository.findByTenantIdAndWorkerTaskId("tenant-a", "task-existing"))
                .thenReturn(Optional.empty());
        BusinessTaskScopedTokenEntity token = token("ROTATING", task.getCreatedAt(), null);
        when(taskTokenRepository.findFirstByWorkerTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
                "task-existing", "tenant-a", "app-a")).thenReturn(Optional.of(token));

        RuntimeTaskAuditDTO audit =
                service.auditTask("runtime-key", "runtime-secret", "user-a", "task-existing");

        assertEquals(RuntimeStateAuditService.TOKEN_UNCONFIRMED, audit.getTaskTokenStatus());
    }

    @Test
    void taskAuditRejectsCrossTenantAndMissingTaskWithoutFallback() {
        SessionTaskEntity foreign = task(LocalDateTime.now(), LocalDateTime.now());
        foreign.setTenantId("tenant-b");
        when(sessionTaskRepository.findByTaskId("foreign-task")).thenReturn(Optional.of(foreign));

        SecurityException forbidden = assertThrows(SecurityException.class,
                () -> service.auditTask("runtime-key", "runtime-secret", "user-a", "foreign-task"));
        assertEquals("RUNTIME_TASK_AUDIT_FORBIDDEN", forbidden.getMessage());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.auditTask("runtime-key", "runtime-secret", "user-a", "missing-task"));
        assertEquals("RUNTIME_TASK_AUDIT_NOT_FOUND", missing.getMessage());
        verify(terminalStateRepository, never()).findByTenantIdAndWorkerTaskId("tenant-a", "missing-task");
    }

    @Test
    void auditMethodsAreReadOnlyTransactions() throws Exception {
        Method binding = RuntimeStateAuditService.class.getMethod(
                "auditBinding",
                String.class, String.class, String.class, String.class, String.class, String.class);
        Method task = RuntimeStateAuditService.class.getMethod(
                "auditTask",
                String.class, String.class, String.class, String.class);

        assertTrue(binding.getAnnotation(Transactional.class).readOnly());
        assertTrue(task.getAnnotation(Transactional.class).readOnly());
    }

    private ResolvedClientAppCredentialDTO owner() {
        return ResolvedClientAppCredentialDTO.builder()
                .tenantId("tenant-a")
                .clientAppId("app-a")
                .credentialId("credential-a")
                .build();
    }

    private SessionTaskEntity task(LocalDateTime created, LocalDateTime updated) {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId("task-existing");
        task.setSessionId("session-a");
        task.setProviderTaskId("provider-task-must-never-be-returned");
        task.setTenantId("tenant-a");
        task.setWorkerId("worker-observed");
        task.setModelConfigId("model-observed");
        task.setModel("codex-luna:high");
        task.setStatus("FAILED");
        task.setCreatedAt(created);
        task.setUpdatedAt(updated);
        task.setTaskStateJson("{\"schemaVersion\":1}");
        return task;
    }

    private BusinessTaskScopedTokenEntity token(
            String status,
            LocalDateTime issuedAt,
            LocalDateTime revokedAt) {
        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setTenantId("tenant-a");
        token.setClientAppId("app-a");
        token.setUpstreamUserId("user-a");
        token.setStatus(status);
        token.setIssuedAt(issuedAt);
        token.setRevokedAt(revokedAt);
        return token;
    }
}
