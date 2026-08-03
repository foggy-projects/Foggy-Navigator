package com.foggy.navigator.session.controller;

import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.common.entity.UserEntity;
import com.foggy.navigator.auth.repository.UserRepository;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.service.ScopedSharedTaskTerminationCommandAdapter;
import com.foggy.navigator.session.service.SharingKeyService;
import com.foggy.navigator.session.service.SessionTaskResourceAccessService;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.RequestHeader;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SharedTaskControllerTest {

    private static final String REQUEST_ID =
            "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private SharingKeyService sharingKeyService;
    @Mock
    private UnifiedAgentResolver agentResolver;
    @Mock
    private TaskDispatchFacade taskDispatchFacade;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SessionTaskResourceAccessService resourceAccessService;
    @Mock
    private ScopedSharedTaskTerminationCommandAdapter taskTerminationCommandAdapter;
    @Mock
    private A2aAgent agent;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void getTask_returnsA2aTaskWhenSharingKeyMatchesTaskAgent() {
        SharedTaskController controller = controller();
        SharingKeyEntity keyEntity = buildSharingKey("agent-1", "owner-1");
        DispatchTaskDTO dispatchTask = DispatchTaskDTO.builder()
                .taskId("task-1")
                .agentId("agent-1")
                .build();
        A2aTask a2aTask = A2aTask.builder().id("task-1").build();

        when(sharingKeyService.validateForKeyOnly("shk-1")).thenReturn(keyEntity);
        when(taskDispatchFacade.getTask(eq("task-1"), any())).thenReturn(Optional.of(dispatchTask));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.getTask("task-1")).thenReturn(Optional.of(a2aTask));

        RX<A2aTask> result = controller.getTask("shk-1", "task-1");

        assertNotNull(result.getData());
        assertEquals("task-1", result.getData().getId());
    }

    @Test
    void getTask_returnsFailWhenTaskAgentDoesNotMatchSharingKey() {
        SharedTaskController controller = controller();
        SharingKeyEntity keyEntity = buildSharingKey("agent-1", "owner-1");
        DispatchTaskDTO dispatchTask = DispatchTaskDTO.builder()
                .taskId("task-1")
                .agentId("agent-2")
                .build();

        when(sharingKeyService.validateForKeyOnly("shk-1")).thenReturn(keyEntity);
        when(taskDispatchFacade.getTask(eq("task-1"), any())).thenReturn(Optional.of(dispatchTask));

        RX<A2aTask> result = controller.getTask("shk-1", "task-1");

        assertNull(result.getData());
        verify(agentResolver, never()).resolveAgent(anyString(), any());
    }

    @Test
    void cancelTask_declaresOptionalClientRequestIdHeader() throws Exception {
        RequestHeader header = SharedTaskController.class
                .getMethod("cancelTask", String.class, String.class, String.class)
                .getParameters()[2]
                .getAnnotation(RequestHeader.class);

        assertNotNull(header);
        assertEquals("X-Navigator-Client-Request-Id", header.value());
        assertFalse(header.required());
    }

    @Test
    void cancelTask_passesRequestIdsAndNeverUsesLegacyFacade() {
        SharedTaskController controller = controller();

        RX<String> absent = controller.cancelTask("shk-1", "task-1", null);
        RX<String> blank = controller.cancelTask("shk-1", "task-1", "  ");
        RX<String> explicit = controller.cancelTask(
                "shk-1", "task-1", REQUEST_ID);

        assertEquals("Task cancelled", absent.getData());
        assertEquals("Task cancelled", blank.getData());
        assertEquals("Task cancelled", explicit.getData());
        verify(taskTerminationCommandAdapter).terminateTask(
                "shk-1", "task-1", null);
        verify(taskTerminationCommandAdapter).terminateTask(
                "shk-1", "task-1", "  ");
        verify(taskTerminationCommandAdapter).terminateTask(
                "shk-1", "task-1", REQUEST_ID);
        verify(taskDispatchFacade, never()).getTask(eq("task-1"), any());
        verify(taskDispatchFacade, never()).cancelTask(anyString(), any(), any());
    }

    @Test
    void cancelTask_clearsAndRestoresAmbientUserOnSuccessAndFailure() {
        SharedTaskController controller = controller();
        CurrentUser ambient = CurrentUser.builder()
                .userId("jwt-user")
                .tenantId("jwt-tenant")
                .build();
        UserContext.setCurrentUser(ambient);
        when(taskTerminationCommandAdapter.terminateTask(
                "shk-1", "task-1", REQUEST_ID))
                .thenAnswer(invocation -> {
                    assertNull(UserContext.getCurrentUser());
                    return new ScopedSharedTaskTerminationCommandAdapter.TerminationResult(
                            "TERMINATION_REQUEST_ACCEPTED", null);
                })
                .thenAnswer(invocation -> {
                    assertNull(UserContext.getCurrentUser());
                    throw new SecurityException("shared resource is not accessible");
                });

        RX<String> success = controller.cancelTask(
                "shk-1", "task-1", REQUEST_ID);
        assertEquals("Task cancelled", success.getData());
        assertSame(ambient, UserContext.getCurrentUser());

        SecurityException failure = assertThrows(SecurityException.class,
                () -> controller.cancelTask("shk-1", "task-1", REQUEST_ID));
        assertEquals("shared resource is not accessible", failure.getMessage());
        assertSame(ambient, UserContext.getCurrentUser());
    }

    @Test
    void cancelTask_mapsAdmissionUnsupportedAndStateFailuresSafely() {
        SharedTaskController controller = controller();
        when(taskTerminationCommandAdapter.terminateTask(
                "shk-1", "task-1", REQUEST_ID))
                .thenThrow(
                        new ScopedSharedTaskTerminationCommandAdapter
                                .SharedTerminationAdmissionRejectedException(
                                "Invalid sharing key"),
                        new UnsupportedOperationException("provider detail"),
                        new IllegalArgumentException("Provider not found: secret-provider"),
                        new IllegalStateException("TERMINATION_EFFECT_AMBIGUOUS"),
                        new IllegalStateException("unsafe provider state"),
                        new org.springframework.dao.PessimisticLockingFailureException(
                                "deadlock"));

        RX<String> admission = controller.cancelTask(
                "shk-1", "task-1", REQUEST_ID);
        RX<String> unsupported = controller.cancelTask(
                "shk-1", "task-1", REQUEST_ID);
        RX<String> providerArgument = controller.cancelTask(
                "shk-1", "task-1", REQUEST_ID);
        RX<String> ambiguous = controller.cancelTask(
                "shk-1", "task-1", REQUEST_ID);
        RX<String> unsafe = controller.cancelTask(
                "shk-1", "task-1", REQUEST_ID);
        RX<String> concurrent = controller.cancelTask(
                "shk-1", "task-1", REQUEST_ID);

        assertEquals("Invalid sharing key", admission.getMsg());
        assertEquals("TERMINATION_REQUEST_NOT_SUPPORTED", unsupported.getMsg());
        assertEquals("TERMINATION_REQUEST_NOT_SUPPORTED", providerArgument.getMsg());
        assertEquals("TERMINATION_EFFECT_AMBIGUOUS", ambiguous.getMsg());
        assertEquals("TERMINATION_REQUEST_FAILED", unsafe.getMsg());
        assertEquals("Failed to cancel task due to concurrent update, please retry",
                concurrent.getMsg());
        assertFalse(providerArgument.getMsg().contains("secret-provider"));
        verifyNoInteractions(taskDispatchFacade);
    }

    @Test
    void respondToTask_staleInteractionReturnsBusinessFailure() {
        SharedTaskController controller = controller();
        SharingKeyEntity keyEntity = buildSharingKey("agent-1", "owner-1");
        DispatchTaskDTO dispatchTask = DispatchTaskDTO.builder()
                .taskId("task-1")
                .agentId("agent-1")
                .build();
        Map<String, Object> body = Map.of(
                "permissionId", "stale", "answers", Map.of("choice", "one"));

        when(sharingKeyService.validateForKeyOnly("shk-1")).thenReturn(keyEntity);
        when(taskDispatchFacade.getTask(eq("task-1"), any())).thenReturn(Optional.of(dispatchTask));
        doThrow(new IllegalStateException("CODEX_USER_INPUT_REQUEST_MISMATCH"))
                .when(taskDispatchFacade).respondToTask(eq("task-1"), any(), eq(body));

        RX<String> result = controller.respondToTask("shk-1", "task-1", body);

        assertNull(result.getData());
        assertTrue(result.getMsg().contains("CODEX_USER_INPUT_REQUEST_MISMATCH"));
    }

    @Test
    void getSessionMessages_returnsConversationWhenSessionBelongsToSharedAgent() {
        SharedTaskController controller = controller();
        SharingKeyEntity keyEntity = buildSharingKey("agent-1", "owner-1");
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("owner-1");
        session.setAgentId("agent-1");
        List<Message> messages = List.of(Message.builder()
                .id("msg-1")
                .sessionId("session-1")
                .role(MessageRole.USER)
                .content("hello")
                .createdAt(LocalDateTime.now())
                .build());

        when(sharingKeyService.validateForKeyOnly("shk-1")).thenReturn(keyEntity);
        when(resourceAccessService.requireOwnedSession("session-1", "owner-1", "tenant-1"))
                .thenReturn(session);
        when(sessionManager.getAllMessages("session-1")).thenReturn(messages);

        RX<List<Message>> result = controller.getSessionMessages("shk-1", "session-1");

        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        assertEquals("hello", result.getData().get(0).getContent());
    }

    @Test
    void getSessionMessages_returnsFailWhenSessionAgentDoesNotMatchSharingKey() {
        SharedTaskController controller = controller();
        SharingKeyEntity keyEntity = buildSharingKey("agent-1", "owner-1");
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("owner-1");
        session.setAgentId("agent-2");

        when(sharingKeyService.validateForKeyOnly("shk-1")).thenReturn(keyEntity);
        when(resourceAccessService.requireOwnedSession("session-1", "owner-1", "tenant-1"))
                .thenReturn(session);

        RX<List<Message>> result = controller.getSessionMessages("shk-1", "session-1");

        assertNull(result.getData());
        verify(sessionManager, never()).getAllMessages(anyString());
    }

    private SharingKeyEntity buildSharingKey(String agentId, String ownerUserId) {
        SharingKeyEntity entity = new SharingKeyEntity();
        entity.setSharingKey("shk-1");
        entity.setAgentId(agentId);
        entity.setOwnerUserId(ownerUserId);
        entity.setEnabled(true);
        UserEntity owner = new UserEntity();
        owner.setId(ownerUserId);
        owner.setTenantId("tenant-1");
        lenient().when(userRepository.findById(ownerUserId)).thenReturn(Optional.of(owner));
        return entity;
    }

    private SharedTaskController controller() {
        return new SharedTaskController(
                sharingKeyService,
                agentResolver,
                taskDispatchFacade,
                sessionManager,
                userRepository,
                resourceAccessService,
                taskTerminationCommandAdapter);
    }
}
