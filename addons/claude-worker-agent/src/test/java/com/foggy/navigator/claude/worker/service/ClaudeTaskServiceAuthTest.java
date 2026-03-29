package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ClaudeTaskService.resolveAuth — auth resolution priority and persistence.
 */
class ClaudeTaskServiceAuthTest {

    private ClaudeTaskService service;
    private ClaudeWorkerService workerService;
    private WorkingDirectoryService directoryService;
    private SessionManager sessionManager;
    private ApplicationEventPublisher publisher;
    private LlmModelManager llmModelManager;
    private CredentialEncryptor credentialEncryptor;
    private com.foggy.navigator.common.repository.WorkingDirectoryRepository workingDirectoryRepository;
    private com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository taskRepository;
    private SessionTaskRepository sessionTaskRepository;
    private SessionEntityRepository sessionEntityRepository;

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String WORKER_ID = "worker-1";
    private static final String SESSION_ID = "session-001";

    @BeforeEach
    void setUp() {
        workerService = mock(ClaudeWorkerService.class);
        directoryService = mock(WorkingDirectoryService.class);
        workingDirectoryRepository = mock(com.foggy.navigator.common.repository.WorkingDirectoryRepository.class);
        sessionManager = mock(SessionManager.class);
        publisher = mock(ApplicationEventPublisher.class);
        llmModelManager = mock(LlmModelManager.class);
        credentialEncryptor = mock(CredentialEncryptor.class);
        taskRepository = mock(com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository.class);
        sessionTaskRepository = mock(SessionTaskRepository.class);
        sessionEntityRepository = mock(SessionEntityRepository.class);

        UserAuthService userAuthService = mock(UserAuthService.class);
        when(userAuthService.generateServiceToken(anyString())).thenReturn("mock-jwt-token");

        var agentTeamsConfigService = mock(AgentTeamsConfigService.class);
        var codingAgentRepository = mock(com.foggy.navigator.claude.worker.repository.CodingAgentRepository.class);
        service = new ClaudeTaskService(
                taskRepository,
                workerService, agentTeamsConfigService, codingAgentRepository, directoryService,
                workingDirectoryRepository,
                sessionManager, publisher, llmModelManager, userAuthService,
                credentialEncryptor,
                mock(org.springframework.transaction.support.TransactionTemplate.class));
        ReflectionTestUtils.setField(service, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(service, "sessionEntityRepository", sessionEntityRepository);

        // Default worker mock (online)
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId(WORKER_ID);
        worker.setUserId(USER_ID);
        worker.setStatus("ONLINE");
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(worker);

        // Session manager mock
        when(sessionManager.createSession(any(SessionCreateRequest.class))).thenReturn(SESSION_ID);

        // Mock existing session for resume tests
        Session existingSession = new Session();
        existingSession.setId(SESSION_ID);
        existingSession.setUserId(USER_ID);
        existingSession.setTenantId(TENANT_ID);
        existingSession.setAgentId("claude-worker");
        existingSession.setTaskName("Test task");
        when(sessionManager.getSession(SESSION_ID)).thenReturn(existingSession);

        when(taskRepository.save(any(ClaudeTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTaskRepository.findByTaskId(anyString())).thenReturn(Optional.empty());
        when(sessionTaskRepository.save(any(SessionTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionEntityRepository.findById(anyString())).thenAnswer(invocation -> {
            SessionEntity session = new SessionEntity();
            session.setId(invocation.getArgument(0));
            session.setProviderType("claude-worker");
            return Optional.of(session);
        });
        when(sessionEntityRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Default: session entity with no auth bound yet (authBoundAt = null)
        // sessionEntityRepository.findById already mocked above to return a SessionEntity
    }

    @Test
    void createTask_withModelConfigId_savesAuthToSession() {
        // Arrange: Platform model config exists
        String modelConfigId = "model-config-123";
        LlmModelConfigDTO modelConfig = createModelConfig(modelConfigId, "GPT-4", "https://api.openai.com/v1");
        when(llmModelManager.getModelConfig(modelConfigId)).thenReturn(Optional.of(modelConfig));
        when(llmModelManager.getDecryptedApiKey(modelConfigId)).thenReturn("sk-test-key-123");
        when(credentialEncryptor.encrypt("sk-test-key-123")).thenReturn("encrypted-sk-test-key-123");

        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Test task");
        form.setCwd("/test/path");
        form.setModelConfigId(modelConfigId);

        // Act
        TaskDTO result = service.createTask(USER_ID, TENANT_ID, form);

        // Assert: Task created successfully
        assertNotNull(result);
        assertEquals(SESSION_ID, result.getSessionId());
        assertEquals(WORKER_ID, result.getWorkerId());

        // Verify SessionEntity was saved with auth fields
        verify(sessionEntityRepository, atLeastOnce()).save(argThat((SessionEntity entity) ->
                SESSION_ID.equals(entity.getId())
                        && "CUSTOM_ENDPOINT".equals(entity.getAuthMode())
                        && "encrypted-sk-test-key-123".equals(entity.getAuthTokenCiphertext())
                        && "https://api.openai.com/v1".equals(entity.getAuthBaseUrl())
                        && entity.getAuthBoundAt() != null
        ));
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                SESSION_ID.equals(entity.getSessionId())
                        && "claude-worker".equals(entity.getProviderType())
                        && entity.getTaskStateJson() != null
                        && entity.getTaskStateJson().contains("\"fileCheckpointingEnabled\":true")
        ));
    }

    @Test
    void createTask_withExplicitLogicalAgentId_persistsItIntoSessionAndTaskProjection() {
        CreateTaskForm form = new CreateTaskForm();
        form.setAgentId("agent-claude-1");
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Test task");
        form.setCwd("/test/path");

        service.createTask(USER_ID, TENANT_ID, form);

        verify(sessionManager).createSession(argThat((SessionCreateRequest request) ->
                "agent-claude-1".equals(request.getAgentId())
                        && "claude-worker".equals(request.getProviderType())
        ));
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                SESSION_ID.equals(entity.getSessionId())
                        && "agent-claude-1".equals(entity.getAgentId())
                        && "claude-worker".equals(entity.getProviderType())
        ));
        verify(sessionEntityRepository, atLeastOnce()).save(argThat((SessionEntity entity) ->
                SESSION_ID.equals(entity.getId())
                        && "agent-claude-1".equals(entity.getAgentId())
                        && "claude-worker".equals(entity.getProviderType())
        ));
    }

    @Test
    void createTask_withModelConfigId_noBaseUrl_usesApiKeyMode() {
        // Arrange: Platform model config without custom baseUrl
        String modelConfigId = "model-config-456";
        LlmModelConfigDTO modelConfig = createModelConfig(modelConfigId, "Claude-3", null);
        when(llmModelManager.getModelConfig(modelConfigId)).thenReturn(Optional.of(modelConfig));
        when(llmModelManager.getDecryptedApiKey(modelConfigId)).thenReturn("sk-ant-key-456");
        when(credentialEncryptor.encrypt("sk-ant-key-456")).thenReturn("encrypted-sk-ant-key-456");

        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Test task");
        form.setCwd("/test/path");
        form.setModelConfigId(modelConfigId);

        // Act
        service.createTask(USER_ID, TENANT_ID, form);

        // Assert: should use API_KEY mode (not CUSTOM_ENDPOINT) — verify SessionEntity
        verify(sessionEntityRepository, atLeastOnce()).save(argThat((SessionEntity entity) ->
                "API_KEY".equals(entity.getAuthMode())
                        && "encrypted-sk-ant-key-456".equals(entity.getAuthTokenCiphertext())
                        && entity.getAuthBaseUrl() == null
                        && entity.getAuthBoundAt() != null
        ));
    }

    @Test
    void resumeTask_existingAuthBound_notOverriddenByModelConfigId() {
        // Arrange: SessionEntity already has auth bound
        // Override the sessionEntityRepository mock to return a session with auth already bound
        SessionEntity boundSession = new SessionEntity();
        boundSession.setId(SESSION_ID);
        boundSession.setUserId(USER_ID);
        boundSession.setProviderType("claude-worker");
        boundSession.setCurrentWorkerId(WORKER_ID);
        boundSession.setAuthMode("API_KEY");
        boundSession.setAuthTokenCiphertext("encrypted-old-key");
        boundSession.setAuthBaseUrl(null);
        boundSession.setAuthBoundAt(LocalDateTime.now().minusHours(1)); // Bound 1 hour ago
        boundSession.setStatus("ACTIVE");
        when(sessionEntityRepository.findById(SESSION_ID)).thenReturn(Optional.of(boundSession));
        when(credentialEncryptor.decrypt("encrypted-old-key")).thenReturn("old-key-789");

        // User provides different modelConfigId in resume request
        String modelConfigId = "model-config-new";
        LlmModelConfigDTO modelConfig = createModelConfig(modelConfigId, "GPT-5", "https://new-api.example.com");
        when(llmModelManager.getModelConfig(modelConfigId)).thenReturn(Optional.of(modelConfig));
        when(llmModelManager.getDecryptedApiKey(modelConfigId)).thenReturn("sk-new-key");

        var form = new com.foggy.navigator.claude.worker.model.form.ResumeTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Resume task");
        form.setSessionId(SESSION_ID);
        form.setClaudeSessionId("claude-session-001");
        form.setModelConfigId(modelConfigId);

        // Mock claudeSessionId existence check
        when(taskRepository.existsByClaudeSessionIdAndWorkerId("claude-session-001", WORKER_ID)).thenReturn(true);
        when(taskRepository.existsByClaudeSessionIdAndWorkerIdAndStatus("claude-session-001", WORKER_ID, "RUNNING")).thenReturn(false);

        // Act
        service.resumeTask(USER_ID, TENANT_ID, form);

        // Assert: encrypt should NOT be called with new key (existing auth is preserved)
        verify(credentialEncryptor, never()).encrypt("sk-new-key");
    }

    @Test
    void resumeTask_noExistingAuth_resolvesFromModelConfigId() {
        // Arrange: No existing auth bound (default session mock has no authBoundAt)

        String modelConfigId = "model-config-789";
        LlmModelConfigDTO modelConfig = createModelConfig(modelConfigId, "GPT-4", "https://api.example.com");
        when(llmModelManager.getModelConfig(modelConfigId)).thenReturn(Optional.of(modelConfig));
        when(llmModelManager.getDecryptedApiKey(modelConfigId)).thenReturn("sk-789");
        when(credentialEncryptor.encrypt("sk-789")).thenReturn("encrypted-sk-789");

        var form = new com.foggy.navigator.claude.worker.model.form.ResumeTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Resume task");
        form.setSessionId(SESSION_ID);
        form.setClaudeSessionId("claude-session-002");
        form.setModelConfigId(modelConfigId);

        // Mock claudeSessionId existence check
        when(taskRepository.existsByClaudeSessionIdAndWorkerId("claude-session-002", WORKER_ID)).thenReturn(true);
        when(taskRepository.existsByClaudeSessionIdAndWorkerIdAndStatus("claude-session-002", WORKER_ID, "RUNNING")).thenReturn(false);

        // Act
        service.resumeTask(USER_ID, TENANT_ID, form);

        // Assert: auth should be saved to SessionEntity
        verify(sessionEntityRepository, atLeastOnce()).save(argThat((SessionEntity entity) ->
                SESSION_ID.equals(entity.getId())
                        && "CUSTOM_ENDPOINT".equals(entity.getAuthMode())
                        && "encrypted-sk-789".equals(entity.getAuthTokenCiphertext())
                        && "https://api.example.com".equals(entity.getAuthBaseUrl())
                        && entity.getAuthBoundAt() != null
        ));
    }

    @Test
    void resumeTask_reusesLogicalAgentIdFromExistingSessionWhenRequestOmitsAgentId() {
        SessionEntity existingSession = new SessionEntity();
        existingSession.setId(SESSION_ID);
        existingSession.setUserId(USER_ID);
        existingSession.setAgentId("agent-claude-1");
        existingSession.setProviderType("claude-worker");
        when(sessionEntityRepository.findById(SESSION_ID)).thenReturn(Optional.of(existingSession));
        when(taskRepository.existsByClaudeSessionIdAndWorkerId("claude-session-003", WORKER_ID)).thenReturn(true);
        when(taskRepository.existsByClaudeSessionIdAndWorkerIdAndStatus("claude-session-003", WORKER_ID, "RUNNING"))
                .thenReturn(false);

        var form = new com.foggy.navigator.claude.worker.model.form.ResumeTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Resume task");
        form.setSessionId(SESSION_ID);
        form.setClaudeSessionId("claude-session-003");

        service.resumeTask(USER_ID, TENANT_ID, form);

        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                SESSION_ID.equals(entity.getSessionId())
                        && "agent-claude-1".equals(entity.getAgentId())
        ));
        verify(sessionEntityRepository, atLeastOnce()).save(argThat((SessionEntity entity) ->
                SESSION_ID.equals(entity.getId())
                        && "agent-claude-1".equals(entity.getAgentId())
        ));
    }

    @Test
    void resolveAuth_modelConfigIdWithNoApiKey_fallsBackToDirectory() {
        // Arrange: Model config exists but hasApiKey=false
        String modelConfigId = "model-config-no-key";
        LlmModelConfigDTO modelConfig = createModelConfig(modelConfigId, "Free Model", null);
        modelConfig.setHasApiKey(false);
        when(llmModelManager.getModelConfig(modelConfigId)).thenReturn(Optional.of(modelConfig));

        // Directory has default auth
        String directoryId = "dir-001";
        WorkingDirectoryEntity dir = createWorkingDirectory(directoryId, "/test/path");
        dir.setDefaultAuthMode("API_KEY");
        dir.setDefaultAuthToken("encrypted-dir-key");
        dir.setDefaultBaseUrl(null);

        when(directoryService.getDirectoryEntity(USER_ID, directoryId)).thenReturn(dir);
        when(workingDirectoryRepository.findByDirectoryIdAndUserId(directoryId, USER_ID))
                .thenReturn(Optional.of(dir));
        when(directoryService.getDecryptedDefaultAuth(dir)).thenReturn(new String[]{"API_KEY", "dir-key-123", null});
        when(credentialEncryptor.encrypt("dir-key-123")).thenReturn("encrypted-dir-key-123");

        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Test task");
        form.setDirectoryId(directoryId);
        form.setModelConfigId(modelConfigId);

        // Act
        service.createTask(USER_ID, TENANT_ID, form);

        // Assert: Should bind from directory (not model config since hasApiKey=false)
        verify(sessionEntityRepository, atLeastOnce()).save(argThat((SessionEntity entity) ->
                SESSION_ID.equals(entity.getId())
                        && "API_KEY".equals(entity.getAuthMode())
                        && "encrypted-dir-key-123".equals(entity.getAuthTokenCiphertext())
                        && entity.getAuthBoundAt() != null
        ));
    }

    private LlmModelConfigDTO createModelConfig(String id, String modelName, String baseUrl) {
        LlmModelConfigDTO dto = new LlmModelConfigDTO();
        dto.setId(id);
        dto.setTenantId(TENANT_ID);
        dto.setName(modelName);
        dto.setCategory(LlmModelCategory.GENERAL);
        dto.setBaseUrl(baseUrl);
        dto.setModelName(modelName);
        dto.setHasApiKey(true);
        dto.setIsDefault(false);
        dto.setCreatedAt(LocalDateTime.now());
        dto.setUpdatedAt(LocalDateTime.now());
        return dto;
    }

    @Test
    void createTask_withRestrictedModelConfigId_deniedForUnauthorizedWorker() {
        // Arrange: RESTRICTED model that does NOT allow WORKER_ID
        String modelConfigId = "restricted-model-001";
        doThrow(new IllegalArgumentException("该模型未授权给当前 Worker 使用: Restricted-Model"))
                .when(llmModelManager).validateModelAccessForWorker(modelConfigId, WORKER_ID);

        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Test task");
        form.setCwd("/test/path");
        form.setModelConfigId(modelConfigId);

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () ->
                service.createTask(USER_ID, TENANT_ID, form));

        // getDecryptedApiKey should never be called
        verify(llmModelManager, never()).getDecryptedApiKey(anyString());
    }

    @Test
    void getTaskById_recoversLogicalAgentIdFromUnifiedSessionStore() {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId("task-1");
        entity.setSessionId(SESSION_ID);
        entity.setWorkerId(WORKER_ID);
        entity.setUserId(USER_ID);
        entity.setPrompt("Test prompt");
        entity.setStatus("RUNNING");
        when(taskRepository.findByTaskId("task-1")).thenReturn(Optional.of(entity));

        SessionTaskEntity sessionTask = new SessionTaskEntity();
        sessionTask.setTaskId("task-1");
        sessionTask.setSessionId(SESSION_ID);
        sessionTask.setAgentId("agent-claude-1");
        when(sessionTaskRepository.findByTaskId("task-1")).thenReturn(Optional.of(sessionTask));

        var result = service.getTaskById("task-1").orElseThrow();

        assertEquals("agent-claude-1", result.getAgentId());
        assertEquals("claude-worker", result.getProviderType());
    }

    // ===== resolveEffectiveModelConfigId priority tests =====

    @Test
    void createTask_noExplicitModelConfig_usesAgentModelOverride() {
        // Arrange: No explicit modelConfigId, but AgentModelOverride exists
        String overrideConfigId = "override-model-config";
        LlmModelConfigDTO overrideConfig = createModelConfig(overrideConfigId, "Override-GPT", "https://override.api.com");
        // resolveModelForAgent(tenantId, agentId, null) → returns the override
        when(llmModelManager.resolveModelForAgent(eq(TENANT_ID), anyString(), isNull()))
                .thenReturn(Optional.of(overrideConfig));
        when(llmModelManager.getModelConfig(overrideConfigId)).thenReturn(Optional.of(overrideConfig));
        when(llmModelManager.getDecryptedApiKey(overrideConfigId)).thenReturn("sk-override-key");
        when(credentialEncryptor.encrypt("sk-override-key")).thenReturn("encrypted-override");

        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Test task");
        form.setCwd("/test/path");
        form.setAgentId("agent-123");
        // No modelConfigId set → should fall through to AgentModelOverride

        service.createTask(USER_ID, TENANT_ID, form);

        // Should use the override config's API key
        verify(llmModelManager).getDecryptedApiKey(overrideConfigId);
    }

    @Test
    void createTask_noExplicitModelConfig_noOverride_usesAgentEntityDefault() {
        // Arrange: No explicit, no override, but Agent entity has defaultModelConfigId
        String agentDefaultConfigId = "agent-default-config";
        LlmModelConfigDTO agentDefaultConfig = createModelConfig(agentDefaultConfigId, "Agent-Default", null);
        when(llmModelManager.resolveModelForAgent(anyString(), anyString(), isNull()))
                .thenReturn(Optional.empty());
        when(llmModelManager.getModelConfig(agentDefaultConfigId)).thenReturn(Optional.of(agentDefaultConfig));
        when(llmModelManager.getDecryptedApiKey(agentDefaultConfigId)).thenReturn("sk-agent-default-key");
        when(credentialEncryptor.encrypt("sk-agent-default-key")).thenReturn("encrypted-agent-default");

        // Mock CodingAgentEntity with defaultModelConfigId
        var codingAgentRepository = mock(com.foggy.navigator.claude.worker.repository.CodingAgentRepository.class);
        com.foggy.navigator.common.entity.CodingAgentEntity agentEntity = new com.foggy.navigator.common.entity.CodingAgentEntity();
        agentEntity.setAgentId("agent-123");
        agentEntity.setDefaultModelConfigId(agentDefaultConfigId);
        when(codingAgentRepository.findByAgentId("agent-123")).thenReturn(Optional.of(agentEntity));

        // Re-create service with this repo
        UserAuthService userAuthService = mock(UserAuthService.class);
        when(userAuthService.generateServiceToken(anyString())).thenReturn("mock-jwt-token");
        var localService = new ClaudeTaskService(
                taskRepository, workerService, mock(AgentTeamsConfigService.class), codingAgentRepository,
                directoryService, workingDirectoryRepository, sessionManager, publisher,
                llmModelManager, userAuthService, credentialEncryptor,
                mock(org.springframework.transaction.support.TransactionTemplate.class));
        ReflectionTestUtils.setField(localService, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(localService, "sessionEntityRepository", sessionEntityRepository);

        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Test task");
        form.setCwd("/test/path");
        form.setAgentId("agent-123");
        // No modelConfigId, no override → use entity default

        localService.createTask(USER_ID, TENANT_ID, form);

        verify(llmModelManager).getDecryptedApiKey(agentDefaultConfigId);
    }

    @Test
    void createTask_explicitModelConfig_overridesAll() {
        // Arrange: Both explicit modelConfigId AND AgentModelOverride exist
        String explicitConfigId = "explicit-config";
        String overrideConfigId = "override-config";

        LlmModelConfigDTO explicitConfig = createModelConfig(explicitConfigId, "Explicit-GPT", null);
        LlmModelConfigDTO overrideConfig = createModelConfig(overrideConfigId, "Override-GPT", null);
        when(llmModelManager.getModelConfig(explicitConfigId)).thenReturn(Optional.of(explicitConfig));
        when(llmModelManager.getDecryptedApiKey(explicitConfigId)).thenReturn("sk-explicit");
        when(credentialEncryptor.encrypt("sk-explicit")).thenReturn("encrypted-explicit");

        // Override exists but should NOT be used
        when(llmModelManager.resolveModelForAgent(anyString(), anyString(), isNull()))
                .thenReturn(Optional.of(overrideConfig));

        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Test task");
        form.setCwd("/test/path");
        form.setAgentId("agent-123");
        form.setModelConfigId(explicitConfigId); // Explicit wins

        service.createTask(USER_ID, TENANT_ID, form);

        // Should use explicit, never touch override
        verify(llmModelManager).getDecryptedApiKey(explicitConfigId);
        verify(llmModelManager, never()).getDecryptedApiKey(overrideConfigId);
    }

    @Test
    void createTask_withClaudeSessionId_passesToWorkerEvent() {
        // Arrange: form has claudeSessionId (from contextStore via A2aAgent)
        String modelConfigId = "model-1";
        LlmModelConfigDTO config = createModelConfig(modelConfigId, "Test", null);
        when(llmModelManager.getModelConfig(modelConfigId)).thenReturn(Optional.of(config));
        when(llmModelManager.getDecryptedApiKey(modelConfigId)).thenReturn("sk-test");
        when(credentialEncryptor.encrypt("sk-test")).thenReturn("encrypted");

        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Continue task");
        form.setCwd("/test/path");
        form.setModelConfigId(modelConfigId);
        form.setClaudeSessionId("claude-sess-resume-123");

        service.createTask(USER_ID, TENANT_ID, form);

        // Verify WorkerTaskStartEvent contains the claudeSessionId
        ArgumentCaptor<com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent> eventCaptor =
                ArgumentCaptor.forClass(com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertEquals("claude-sess-resume-123",
                eventCaptor.getValue().getProviderConfigString("claudeSessionId"),
                "claudeSessionId 应传递到 WorkerTaskStartEvent.providerConfig");
    }

    @Test
    void createTask_noClaudeSessionId_sendsEmptyString() {
        // Arrange: No claudeSessionId (new session)
        String modelConfigId = "model-1";
        LlmModelConfigDTO config = createModelConfig(modelConfigId, "Test", null);
        when(llmModelManager.getModelConfig(modelConfigId)).thenReturn(Optional.of(config));
        when(llmModelManager.getDecryptedApiKey(modelConfigId)).thenReturn("sk-test");
        when(credentialEncryptor.encrypt("sk-test")).thenReturn("encrypted");

        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("New task");
        form.setCwd("/test/path");
        form.setModelConfigId(modelConfigId);
        // No claudeSessionId

        service.createTask(USER_ID, TENANT_ID, form);

        ArgumentCaptor<com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent> eventCaptor =
                ArgumentCaptor.forClass(com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertEquals("",
                eventCaptor.getValue().getProviderConfigString("claudeSessionId"),
                "无 claudeSessionId 时应传空字符串");
    }

    private WorkingDirectoryEntity createWorkingDirectory(String directoryId, String path) {
        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(directoryId);
        entity.setWorkerId(WORKER_ID);
        entity.setUserId(USER_ID);
        entity.setTenantId(TENANT_ID);
        entity.setProjectName("test-project");
        entity.setPath(path);
        entity.setDirectoryType("STANDARD");
        return entity;
    }
}
