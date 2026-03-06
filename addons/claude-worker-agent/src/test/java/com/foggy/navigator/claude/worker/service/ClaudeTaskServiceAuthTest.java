package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.entity.ConversationConfigEntity;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

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
    private ConversationConfigService configService;
    private WorkingDirectoryService directoryService;
    private SessionManager sessionManager;
    private ApplicationEventPublisher publisher;
    private LlmModelManager llmModelManager;
    private com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository workingDirectoryRepository;
    private com.foggy.navigator.claude.worker.repository.DeletedClaudeSessionRepository deletedSessionRepository;
    private com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository taskRepository;

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String WORKER_ID = "worker-1";
    private static final String SESSION_ID = "session-001";
    private static final String TASK_ID = "task-001";

    @BeforeEach
    void setUp() {
        workerService = mock(ClaudeWorkerService.class);
        configService = mock(ConversationConfigService.class);
        directoryService = mock(WorkingDirectoryService.class);
        workingDirectoryRepository = mock(com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository.class);
        deletedSessionRepository = mock(com.foggy.navigator.claude.worker.repository.DeletedClaudeSessionRepository.class);
        sessionManager = mock(SessionManager.class);
        publisher = mock(ApplicationEventPublisher.class);
        llmModelManager = mock(LlmModelManager.class);
        taskRepository = mock(com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository.class);

        UserAuthService userAuthService = mock(UserAuthService.class);
        when(userAuthService.generateServiceToken(anyString())).thenReturn("mock-jwt-token");

        var conversationConfigRepository = mock(com.foggy.navigator.claude.worker.repository.ConversationConfigRepository.class);
        var agentTeamsConfigService = mock(AgentTeamsConfigService.class);
        service = new ClaudeTaskService(
                taskRepository,
                conversationConfigRepository,
                deletedSessionRepository,
                workerService, configService, agentTeamsConfigService, directoryService,
                workingDirectoryRepository,
                sessionManager, publisher, llmModelManager, userAuthService,
                mock(org.springframework.transaction.support.TransactionTemplate.class));

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

        // Mock task repository to return task with fixed ID
        com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity taskEntity = new com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity();
        taskEntity.setTaskId(TASK_ID);
        taskEntity.setSessionId(SESSION_ID);
        taskEntity.setWorkerId(WORKER_ID);
        taskEntity.setUserId(USER_ID);
        when(taskRepository.save(any())).thenReturn(taskEntity);

        // Default: no existing conversation config (not bound yet)
        ConversationConfigEntity emptyConfig = createConversationConfig(SESSION_ID, WORKER_ID, USER_ID);
        // Ensure authBoundAt is null
        assertNull(emptyConfig.getAuthBoundAt(), "emptyConfig should have null authBoundAt");
        when(configService.getOrCreate(SESSION_ID, WORKER_ID, USER_ID)).thenReturn(emptyConfig);

        // Stub bindAuthFromDirectory as void method
        doNothing().when(configService).bindAuthFromDirectory(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createTask_withModelConfigId_savesToConversationConfig() {
        // Arrange: Platform model config exists
        String modelConfigId = "model-config-123";
        LlmModelConfigDTO modelConfig = createModelConfig(modelConfigId, "GPT-4", "https://api.openai.com/v1");
        when(llmModelManager.getModelConfig(modelConfigId)).thenReturn(Optional.of(modelConfig));
        when(llmModelManager.getDecryptedApiKey(modelConfigId)).thenReturn("sk-test-key-123");

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

        // Verify bindAuthFromDirectory was called with correct params
        ArgumentCaptor<String> sessionIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> workerIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> authModeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> baseUrlCaptor = ArgumentCaptor.forClass(String.class);

        verify(configService).bindAuthFromDirectory(
                sessionIdCaptor.capture(), workerIdCaptor.capture(), userIdCaptor.capture(),
                authModeCaptor.capture(), tokenCaptor.capture(), baseUrlCaptor.capture());

        assertEquals(SESSION_ID, sessionIdCaptor.getValue());
        assertEquals(WORKER_ID, workerIdCaptor.getValue());
        assertEquals(USER_ID, userIdCaptor.getValue());
        assertEquals("CUSTOM_ENDPOINT", authModeCaptor.getValue());
        assertEquals("sk-test-key-123", tokenCaptor.getValue());
        assertEquals("https://api.openai.com/v1", baseUrlCaptor.getValue());
    }

    @Test
    void createTask_withModelConfigId_noBaseUrl_usesApiKeyMode() {
        // Arrange: Platform model config without custom baseUrl
        String modelConfigId = "model-config-456";
        LlmModelConfigDTO modelConfig = createModelConfig(modelConfigId, "Claude-3", null);
        when(llmModelManager.getModelConfig(modelConfigId)).thenReturn(Optional.of(modelConfig));
        when(llmModelManager.getDecryptedApiKey(modelConfigId)).thenReturn("sk-ant-key-456");

        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Test task");
        form.setCwd("/test/path");
        form.setModelConfigId(modelConfigId);

        // Act
        service.createTask(USER_ID, TENANT_ID, form);

        // Assert: should use API_KEY mode (not CUSTOM_ENDPOINT)
        ArgumentCaptor<String> authModeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> baseUrlCaptor = ArgumentCaptor.forClass(String.class);
        verify(configService).bindAuthFromDirectory(
                anyString(), anyString(), anyString(),
                authModeCaptor.capture(), tokenCaptor.capture(), baseUrlCaptor.capture());

        assertEquals("API_KEY", authModeCaptor.getValue());
        assertEquals("sk-ant-key-456", tokenCaptor.getValue());
        assertNull(baseUrlCaptor.getValue());
    }

    @Test
    void resumeTask_existingAuthBound_notOverriddenByModelConfigId() {
        // Arrange: ConversationConfig already has auth bound
        ConversationConfigEntity existingConfig = createConversationConfig(SESSION_ID, WORKER_ID, USER_ID);
        existingConfig.setAuthMode("API_KEY");
        existingConfig.setAuthToken("encrypted-old-key");
        existingConfig.setBaseUrl(null);
        existingConfig.setAuthBoundAt(LocalDateTime.now().minusHours(1)); // Bound 1 hour ago
        when(configService.getOrCreate(SESSION_ID, WORKER_ID, USER_ID)).thenReturn(existingConfig);
        when(configService.getDecryptedToken(existingConfig)).thenReturn("old-key-789");

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

        // Assert: bindAuthFromDirectory should NOT be called (existing auth is preserved)
        verify(configService, never()).bindAuthFromDirectory(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void resumeTask_noExistingAuth_resolvesFromModelConfigId() {
        // Arrange: No existing auth bound
        ConversationConfigEntity emptyConfig = createConversationConfig(SESSION_ID, WORKER_ID, USER_ID);
        when(configService.getOrCreate(SESSION_ID, WORKER_ID, USER_ID)).thenReturn(emptyConfig);

        String modelConfigId = "model-config-789";
        LlmModelConfigDTO modelConfig = createModelConfig(modelConfigId, "GPT-4", "https://api.example.com");
        when(llmModelManager.getModelConfig(modelConfigId)).thenReturn(Optional.of(modelConfig));
        when(llmModelManager.getDecryptedApiKey(modelConfigId)).thenReturn("sk-789");

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

        // Assert: bindAuthFromDirectory should be called (no existing auth)
        verify(configService).bindAuthFromDirectory(
                eq(SESSION_ID), eq(WORKER_ID), eq(USER_ID),
                eq("CUSTOM_ENDPOINT"), eq("sk-789"), eq("https://api.example.com"));
    }

    @Test
    void resolveAuth_modelConfigIdWithNoApiKey_fallsBackToDirectory() {
        // This test validates the private resolveAuth method through public API behavior
        // We can't test resolveAuth directly since it's private, but we can verify
        // that when modelConfigId has no API key, directory auth is used

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

        when(configService.getOrCreate(SESSION_ID, WORKER_ID, USER_ID))
                .thenReturn(createConversationConfig(SESSION_ID, WORKER_ID, USER_ID));
        when(directoryService.getDirectoryEntity(USER_ID, directoryId)).thenReturn(dir);
        when(workingDirectoryRepository.findByDirectoryIdAndUserId(directoryId, USER_ID))
                .thenReturn(Optional.of(dir));
        when(directoryService.getDecryptedDefaultAuth(dir)).thenReturn(new String[]{"API_KEY", "dir-key-123", null});

        CreateTaskForm form = new CreateTaskForm();
        form.setWorkerId(WORKER_ID);
        form.setPrompt("Test task");
        form.setDirectoryId(directoryId);
        form.setModelConfigId(modelConfigId);

        // Act
        service.createTask(USER_ID, TENANT_ID, form);

        // Assert: Should bind from directory (not model config since hasApiKey=false)
        verify(configService).bindAuthFromDirectory(
                eq(SESSION_ID), eq(WORKER_ID), eq(USER_ID),
                eq("API_KEY"), eq("dir-key-123"), isNull());
    }

    private ConversationConfigEntity createConversationConfig(String sessionId, String workerId, String userId) {
        ConversationConfigEntity entity = new ConversationConfigEntity();
        entity.setSessionId(sessionId);
        entity.setWorkerId(workerId);
        entity.setUserId(userId);
        return entity;
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