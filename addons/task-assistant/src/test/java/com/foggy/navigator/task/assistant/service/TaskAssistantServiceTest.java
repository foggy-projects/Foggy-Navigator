package com.foggy.navigator.task.assistant.service;

import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggy.navigator.spi.notification.UserNotificationSender;
import com.foggy.navigator.spi.task.AgentTaskManager;
import com.foggy.navigator.task.assistant.entity.TaskAssistantConfigEntity;
import com.foggy.navigator.task.assistant.repository.TaskAssistantConfigRepository;
import com.foggy.navigator.task.assistant.spi.TaskAssistantConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskAssistantServiceTest {

    @Mock private TaskAssistantConfigRepository configRepository;
    @Mock private UserNotificationSender notificationSender;
    @Mock private ClaudeWorkerFacade claudeWorkerFacade;
    @Mock private AgentTaskManager agentTaskManager;
    @Mock private SessionManager sessionManager;

    private TaskAssistantService service;

    private static final String USER_ID = "user-1";
    private static final String WORKER_ID = "worker-1";
    private static final String DIRECTORY_ID = "dir-1";
    private static final String SESSION_ID = "foggy-session-1";
    private static final String CLAUDE_SESSION_ID = "claude-session-1";

    @BeforeEach
    void setUp() {
        service = new TaskAssistantService(configRepository, notificationSender,
                claudeWorkerFacade, agentTaskManager, sessionManager);
    }

    private TaskAssistantConfigEntity createConfig() {
        TaskAssistantConfigEntity entity = new TaskAssistantConfigEntity();
        entity.setUserId(USER_ID);
        entity.setEnabled(true);
        entity.setWorkerId(WORKER_ID);
        entity.setCwd("/home/user/foggy-assistant");
        entity.setDirectoryId(DIRECTORY_ID);
        entity.setFoggySessionId(SESSION_ID);
        entity.setClaudeSessionId(CLAUDE_SESSION_ID);
        entity.setModelConfigId("model-config-1");
        entity.setModel("claude-sonnet-4-20250514");
        return entity;
    }

    // --- isAvailable ---

    @Nested
    class IsAvailable {

        @Test
        void returnsTrueWhenConfigComplete() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));

            assertThat(service.isAvailable(USER_ID)).isTrue();
        }

        @Test
        void returnsFalseWhenNoConfig() {
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            assertThat(service.isAvailable(USER_ID)).isFalse();
        }

        @Test
        void returnsFalseWhenDisabled() {
            TaskAssistantConfigEntity config = createConfig();
            config.setEnabled(false);
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));

            assertThat(service.isAvailable(USER_ID)).isFalse();
        }

        @Test
        void returnsFalseWhenWorkerIdNull() {
            TaskAssistantConfigEntity config = createConfig();
            config.setWorkerId(null);
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));

            assertThat(service.isAvailable(USER_ID)).isFalse();
        }

        @Test
        void returnsFalseWhenCwdNull() {
            TaskAssistantConfigEntity config = createConfig();
            config.setCwd(null);
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));

            assertThat(service.isAvailable(USER_ID)).isFalse();
        }

        @Test
        void returnsFalseWhenClaudeWorkerFacadeNull() {
            TaskAssistantService svc = new TaskAssistantService(
                    configRepository, notificationSender, null, agentTaskManager, sessionManager);

            assertThat(svc.isAvailable(USER_ID)).isFalse();
        }
    }

    // --- getConfig ---

    @Nested
    class GetConfig {

        @Test
        void returnsDTOWhenConfigExists() {
            TaskAssistantConfigEntity entity = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(entity));

            Optional<TaskAssistantConfig> result = service.getConfig(USER_ID);

            assertThat(result).isPresent();
            TaskAssistantConfig dto = result.get();
            assertThat(dto.getUserId()).isEqualTo(USER_ID);
            assertThat(dto.getWorkerId()).isEqualTo(WORKER_ID);
            assertThat(dto.getDirectoryId()).isEqualTo(DIRECTORY_ID);
            assertThat(dto.getFoggySessionId()).isEqualTo(SESSION_ID);
            assertThat(dto.getClaudeSessionId()).isEqualTo(CLAUDE_SESSION_ID);
            assertThat(dto.getEnabled()).isTrue();
        }

        @Test
        void returnsEmptyWhenNoConfig() {
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            assertThat(service.getConfig(USER_ID)).isEmpty();
        }
    }

    // --- getAgentCard ---

    @Test
    void getAgentCardReturnsValidCard() {
        var card = service.getAgentCard();

        assertThat(card.getName()).isEqualTo("Task Assistant");
        assertThat(card.getVersion()).isEqualTo("2.0.0");
        assertThat(card.getSkills()).hasSize(1);
        assertThat(card.getSkills().get(0).getId()).isEqualTo("task-notification");
        assertThat(card.getCapabilities().getStreaming()).isFalse();
        assertThat(card.getCapabilities().getPushNotifications()).isTrue();
    }

    // --- processEvents ---

    @Nested
    class ProcessEvents {

        @Test
        void skipsWhenNoConfig() {
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            verifyNoInteractions(claudeWorkerFacade, agentTaskManager);
        }

        @Test
        void skipsWhenDisabled() {
            TaskAssistantConfigEntity config = createConfig();
            config.setEnabled(false);
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            verifyNoInteractions(claudeWorkerFacade, agentTaskManager);
        }

        @Test
        void skipsWhenWorkerIdNull() {
            TaskAssistantConfigEntity config = createConfig();
            config.setWorkerId(null);
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            verifyNoInteractions(claudeWorkerFacade);
        }

        @Test
        void successfulProcessing() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());
            when(agentTaskManager.createTask(anyString(), eq(USER_ID), eq("task-assistant"),
                    eq("task-assistant"), eq("ASSISTANT_EVENT"), anyString(), isNull(), isNull()))
                    .thenReturn("agent-task-1");

            String responseJson = "{\"notification\":{\"title\":\"Task done\",\"body\":\"OK\",\"severity\":\"success\"}}";
            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", responseJson);
            syncResult.put("claudeSessionId", "new-claude-session");
            when(claudeWorkerFacade.syncQueryTracked(eq(USER_ID), eq(WORKER_ID), anyString(),
                    eq("/home/user/foggy-assistant"), eq(CLAUDE_SESSION_ID), eq(4),
                    eq("claude-sonnet-4-20250514"), eq(SESSION_ID), eq(DIRECTORY_ID)))
                    .thenReturn(syncResult);

            List<Map<String, Object>> events = List.of(
                    Map.of("type", "task_completed", "taskId", "t-1", "status", "SUCCESS"));

            service.processEvents(USER_ID, events);

            // AgentTask created and completed
            verify(agentTaskManager).createTask(eq(SESSION_ID), eq(USER_ID), eq("task-assistant"),
                    eq("task-assistant"), eq("ASSISTANT_EVENT"), anyString(), isNull(), isNull());
            verify(agentTaskManager).completeTask("agent-task-1", "COMPLETED", responseJson);

            // Notification sent
            verify(notificationSender).sendNotification(eq(USER_ID), any());

            // claudeSessionId updated
            verify(configRepository).save(argThat(e ->
                    "new-claude-session".equals(e.getClaudeSessionId())));
        }

        @Test
        void handlesErrorFromSyncQuery() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());
            when(agentTaskManager.createTask(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn("agent-task-1");

            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "Worker timeout");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(errorResult);

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            // AgentTask completed as FAILED
            verify(agentTaskManager).completeTask("agent-task-1", "FAILED", "Error: Worker timeout");

            // No notification sent
            verify(notificationSender, never()).sendNotification(anyString(), any());
        }

        @Test
        void handlesExceptionFromSyncQuery() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());
            when(agentTaskManager.createTask(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn("agent-task-1");

            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Connection refused"));

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            // AgentTask completed as FAILED with exception message
            verify(agentTaskManager).completeTask(eq("agent-task-1"), eq("FAILED"),
                    eq("Connection refused"));
        }

        @Test
        void parsesMarkdownWrappedJson() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());

            // Response wrapped in markdown code block
            String responseText = "```json\n{\"notification\":{\"title\":\"Done\"}}\n```";
            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", responseText);
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            // Notification should still be sent (markdown stripped)
            verify(notificationSender).sendNotification(eq(USER_ID), any());
        }

        @Test
        void sendsRawTextWhenJsonParseFails() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());

            // Non-JSON response
            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "This is a plain text response");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            // Still sends notification (as raw text)
            verify(notificationSender).sendNotification(eq(USER_ID), any());
        }

        @Test
        void skipsNotificationWhenResultTextBlank() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "   ");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            verify(notificationSender, never()).sendNotification(anyString(), any());
        }

        @Test
        void processEventsWithoutAgentTaskManager() {
            TaskAssistantService svc = new TaskAssistantService(
                    configRepository, notificationSender, claudeWorkerFacade, null, sessionManager);

            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "{\"notification\":{\"title\":\"OK\"}}");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            svc.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            // No AgentTask interactions but notification still sent
            verifyNoInteractions(agentTaskManager);
            verify(notificationSender).sendNotification(eq(USER_ID), any());
        }
    }

    // --- ensureFoggySession (tested via processEvents) ---

    @Nested
    class EnsureFoggySession {

        @Test
        void reusesExistingSession() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "{}");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            // Should NOT create a new session
            verify(sessionManager, never()).createSession(any());
        }

        @Test
        void recreatesDeletedSession() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));

            // Existing session was deleted; after recreation, return valid session
            when(sessionManager.getSession(SESSION_ID)).thenReturn(null);
            when(sessionManager.getSession("new-session-id")).thenReturn(
                    Session.builder().id("new-session-id").userId(USER_ID).build());
            when(sessionManager.createSession(any())).thenReturn("new-session-id");

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "{}");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            // New session created (first ensureFoggySession call)
            verify(sessionManager).createSession(any());
            // Config saved with new foggySessionId
            verify(configRepository, atLeastOnce()).save(argThat(e ->
                    "new-session-id".equals(e.getFoggySessionId())));
        }

        @Test
        void lazyCreatesWhenNoSessionId() {
            TaskAssistantConfigEntity config = createConfig();
            config.setFoggySessionId(null);
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.createSession(any())).thenReturn("new-session-id");
            // After first create, second ensureFoggySession call sees valid session
            when(sessionManager.getSession("new-session-id")).thenReturn(
                    Session.builder().id("new-session-id").userId(USER_ID).build());

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "{}");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            verify(sessionManager).createSession(any());
        }

        @Test
        void fallsBackToConstantWhenSessionCreateFails() {
            TaskAssistantConfigEntity config = createConfig();
            config.setFoggySessionId(null);
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.createSession(any())).thenThrow(new RuntimeException("DB down"));

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "{}");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), eq("task-assistant"), anyString()))
                    .thenReturn(syncResult);

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            // Should still proceed with fallback sessionId "task-assistant"
            verify(claudeWorkerFacade).syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), eq("task-assistant"), anyString());
        }

        @Test
        void fallsBackWhenSessionManagerNull() {
            TaskAssistantService svc = new TaskAssistantService(
                    configRepository, notificationSender, claudeWorkerFacade, agentTaskManager, null);

            TaskAssistantConfigEntity config = createConfig();
            config.setFoggySessionId(null);
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "{}");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), eq("task-assistant"), anyString()))
                    .thenReturn(syncResult);

            svc.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            verify(claudeWorkerFacade).syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), eq("task-assistant"), anyString());
        }
    }

    // --- repairConfigIfNeeded (tested via processEvents) ---

    @Nested
    class RepairConfig {

        @Test
        void repairsCwdContainingTilde() {
            TaskAssistantConfigEntity config = createConfig();
            config.setCwd("~/foggy-assistant");
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());
            when(claudeWorkerFacade.initDirectory(eq(USER_ID), eq(WORKER_ID),
                    eq("~/foggy-assistant"), anyMap())).thenReturn(DIRECTORY_ID);
            when(claudeWorkerFacade.getDirectoryPath(USER_ID, DIRECTORY_ID))
                    .thenReturn("/home/user/foggy-assistant");

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "{}");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            // cwd repaired and saved
            verify(configRepository, atLeastOnce()).save(argThat(e ->
                    "/home/user/foggy-assistant".equals(e.getCwd())));
        }

        @Test
        void rebindsModelConfigOnRepair() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "{}");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            // rebind called for model config
            verify(claudeWorkerFacade).bindDirectoryModelConfig(USER_ID, DIRECTORY_ID, "model-config-1");
        }
    }

    // --- createOrUpdate ---

    @Nested
    class CreateOrUpdate {

        @Test
        void createsNewConfig() {
            when(claudeWorkerFacade.initDirectory(eq(USER_ID), eq(WORKER_ID),
                    eq("~/foggy-assistant"), anyMap())).thenReturn(DIRECTORY_ID);
            when(claudeWorkerFacade.getDirectoryPath(USER_ID, DIRECTORY_ID))
                    .thenReturn("/home/user/foggy-assistant");
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionManager.createSession(any())).thenReturn(SESSION_ID);

            TaskAssistantConfig result = service.createOrUpdate(USER_ID, WORKER_ID,
                    null, "model-config-1", "claude-sonnet-4-20250514");

            assertThat(result.getUserId()).isEqualTo(USER_ID);
            assertThat(result.getWorkerId()).isEqualTo(WORKER_ID);
            assertThat(result.getCwd()).isEqualTo("/home/user/foggy-assistant");
            assertThat(result.getEnabled()).isTrue();

            verify(claudeWorkerFacade).getWorker(USER_ID, WORKER_ID);
            verify(claudeWorkerFacade).bindDirectoryModelConfig(USER_ID, DIRECTORY_ID, "model-config-1");
        }

        @Test
        void updatesExistingConfig() {
            TaskAssistantConfigEntity existing = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
            when(claudeWorkerFacade.initDirectory(anyString(), anyString(), anyString(), anyMap()))
                    .thenReturn("new-dir-id");
            when(claudeWorkerFacade.getDirectoryPath(USER_ID, "new-dir-id"))
                    .thenReturn("/home/user/new-dir");
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());

            TaskAssistantConfig result = service.createOrUpdate(USER_ID, WORKER_ID,
                    "/home/user/new-dir", "model-config-2", "claude-opus-4-20250514");

            assertThat(result.getDirectoryId()).isEqualTo("new-dir-id");
            assertThat(result.getCwd()).isEqualTo("/home/user/new-dir");
            // claudeSessionId reset on createOrUpdate
            assertThat(result.getClaudeSessionId()).isNull();
        }

        @Test
        void throwsWhenClaudeWorkerFacadeNull() {
            TaskAssistantService svc = new TaskAssistantService(
                    configRepository, notificationSender, null, agentTaskManager, sessionManager);

            assertThatThrownBy(() -> svc.createOrUpdate(USER_ID, WORKER_ID,
                    "~/path", "mc1", "model"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Claude Worker module is not available");
        }

        @Test
        void usesDefaultPathWhenDirectoryPathNull() {
            when(claudeWorkerFacade.initDirectory(eq(USER_ID), eq(WORKER_ID),
                    eq("~/foggy-assistant"), anyMap())).thenReturn(DIRECTORY_ID);
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionManager.createSession(any())).thenReturn(SESSION_ID);

            service.createOrUpdate(USER_ID, WORKER_ID, null, null, "model");

            verify(claudeWorkerFacade).initDirectory(eq(USER_ID), eq(WORKER_ID),
                    eq("~/foggy-assistant"), anyMap());
        }

        @Test
        void usesDefaultPathWhenDirectoryPathBlank() {
            when(claudeWorkerFacade.initDirectory(eq(USER_ID), eq(WORKER_ID),
                    eq("~/foggy-assistant"), anyMap())).thenReturn(DIRECTORY_ID);
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(sessionManager.createSession(any())).thenReturn(SESSION_ID);

            service.createOrUpdate(USER_ID, WORKER_ID, "  ", null, "model");

            verify(claudeWorkerFacade).initDirectory(eq(USER_ID), eq(WORKER_ID),
                    eq("~/foggy-assistant"), anyMap());
        }
    }

    // --- setEnabled ---

    @Nested
    class SetEnabled {

        @Test
        void updatesExistingConfig() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));

            service.setEnabled(USER_ID, false);

            verify(configRepository).save(argThat(e -> Boolean.FALSE.equals(e.getEnabled())));
        }

        @Test
        void createsNewConfigIfNotExists() {
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            service.setEnabled(USER_ID, true);

            verify(configRepository).save(argThat(e ->
                    USER_ID.equals(e.getUserId()) && Boolean.TRUE.equals(e.getEnabled())));
        }
    }

    // --- delete ---

    @Nested
    class Delete {

        @Test
        void deletesExistingConfig() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));

            service.delete(USER_ID);

            verify(configRepository).delete(config);
        }

        @Test
        void noOpWhenNoConfig() {
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

            service.delete(USER_ID);

            verify(configRepository, never()).delete(any());
        }
    }

    // --- dailySummary ---

    @Nested
    class DailySummary {

        @Test
        void skipsWhenClaudeWorkerFacadeNull() {
            TaskAssistantService svc = new TaskAssistantService(
                    configRepository, notificationSender, null, agentTaskManager, sessionManager);

            svc.dailySummary();

            verifyNoInteractions(configRepository);
        }

        @Test
        void processesAllEnabledConfigs() {
            TaskAssistantConfigEntity config1 = createConfig();
            config1.setUserId("user-1");
            config1.setFoggySessionId("session-1");

            TaskAssistantConfigEntity config2 = createConfig();
            config2.setUserId("user-2");
            config2.setFoggySessionId("session-2");

            when(configRepository.findAllByEnabledTrue()).thenReturn(List.of(config1, config2));
            when(sessionManager.getSession(anyString())).thenReturn(
                    Session.builder().id("any").userId("any").build());

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "{\"notification\":{\"title\":\"Summary\"}}");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            service.dailySummary();

            // syncQueryTracked called for each user
            verify(claudeWorkerFacade, times(2)).syncQueryTracked(
                    anyString(), anyString(), anyString(),
                    anyString(), anyString(), eq(5), anyString(), anyString(), anyString());
        }

        @Test
        void skipsConfigWithNullWorkerId() {
            TaskAssistantConfigEntity config = createConfig();
            config.setWorkerId(null);
            when(configRepository.findAllByEnabledTrue()).thenReturn(List.of(config));

            service.dailySummary();

            verifyNoInteractions(claudeWorkerFacade);
        }

        @Test
        void continuesOnErrorForOneUser() {
            TaskAssistantConfigEntity config1 = createConfig();
            config1.setUserId("user-1");
            config1.setFoggySessionId("session-1");

            TaskAssistantConfigEntity config2 = createConfig();
            config2.setUserId("user-2");
            config2.setFoggySessionId("session-2");

            when(configRepository.findAllByEnabledTrue()).thenReturn(List.of(config1, config2));
            when(sessionManager.getSession("session-1")).thenThrow(new RuntimeException("DB error"));
            when(sessionManager.getSession("session-2")).thenReturn(
                    Session.builder().id("session-2").userId("user-2").build());

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "{\"notification\":{\"title\":\"OK\"}}");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            // Should not throw — continues to process user-2
            service.dailySummary();

            // user-2 still processed
            verify(claudeWorkerFacade, atLeastOnce()).syncQueryTracked(
                    eq("user-2"), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
        }
    }

    // --- updateSessionId ---

    @Nested
    class UpdateSessionId {

        @Test
        void updatesWhenNewSessionIdDiffers() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "{}");
            syncResult.put("claudeSessionId", "new-session-abc");
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            verify(configRepository, atLeastOnce()).save(argThat(e ->
                    "new-session-abc".equals(e.getClaudeSessionId())));
        }

        @Test
        void doesNotUpdateWhenSameSessionId() {
            TaskAssistantConfigEntity config = createConfig();
            when(configRepository.findByUserId(USER_ID)).thenReturn(Optional.of(config));
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());

            Map<String, Object> syncResult = new HashMap<>();
            syncResult.put("resultText", "{}");
            syncResult.put("claudeSessionId", CLAUDE_SESSION_ID); // same
            when(claudeWorkerFacade.syncQueryTracked(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                    .thenReturn(syncResult);

            service.processEvents(USER_ID, List.of(Map.of("type", "task_completed")));

            // Only repairConfig save (for bindDirectoryModelConfig), no updateSessionId save
            // The config may be saved by repairConfig, but claudeSessionId should remain unchanged
            verify(configRepository, never()).save(argThat(e ->
                    !"claude-session-1".equals(e.getClaudeSessionId())
                            && e.getClaudeSessionId() != null));
        }
    }
}
