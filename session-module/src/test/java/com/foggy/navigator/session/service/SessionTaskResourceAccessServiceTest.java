package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionTaskResourceAccessServiceTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String DENIED_MESSAGE = "Resource access denied";

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private SessionTaskRepository sessionTaskRepository;

    private SessionTaskResourceAccessService service;

    @BeforeEach
    void setUp() {
        service = new SessionTaskResourceAccessService(sessionRepository, sessionTaskRepository);
    }

    @Test
    void requireOwnedSession_exactUserAndTenant_returnsSession() {
        SessionEntity session = ownedSession("session-1", USER_ID, TENANT_ID);
        when(sessionRepository.findByIdAndUserIdAndTenantId("session-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.of(session));

        SessionEntity result = service.requireOwnedSession("session-1", USER_ID, TENANT_ID);

        assertSame(session, result);
    }

    @Test
    void requireOwnedSession_tenantlessActorAndResourceWithSameUser_returnsSession() {
        SessionEntity session = ownedSession("session-1", USER_ID, null);
        when(sessionRepository.findTenantlessByIdAndUserId("session-1", USER_ID))
                .thenReturn(Optional.of(session));

        SessionEntity result = service.requireOwnedSession("session-1", USER_ID, null);

        assertSame(session, result);
    }

    @Test
    void requireOwnedSession_tenantlessActorCannotAccessTenantBoundOrOtherUsersResource() {
        when(sessionRepository.findTenantlessByIdAndUserId("tenant-bound", USER_ID))
                .thenReturn(Optional.empty());
        when(sessionRepository.findTenantlessByIdAndUserId("other-user-session", USER_ID))
                .thenReturn(Optional.of(ownedSession("other-user-session", "other-user", null)));

        assertDenied(() -> service.requireOwnedSession("tenant-bound", USER_ID, null));
        assertDenied(() -> service.requireOwnedSession("other-user-session", USER_ID, null));
    }

    @Test
    void requireOwnedSession_missingResourceAndWrongOwner_shareGenericFailure() {
        when(sessionRepository.findByIdAndUserIdAndTenantId("missing", USER_ID, TENANT_ID))
                .thenReturn(Optional.empty());
        when(sessionRepository.findByIdAndUserIdAndTenantId("session-1", "other-user", TENANT_ID))
                .thenReturn(Optional.empty());

        SecurityException missing = assertThrows(SecurityException.class,
                () -> service.requireOwnedSession("missing", USER_ID, TENANT_ID));
        SecurityException wrongOwner = assertThrows(SecurityException.class,
                () -> service.requireOwnedSession("session-1", "other-user", TENANT_ID));

        assertEquals(DENIED_MESSAGE, missing.getMessage());
        assertEquals(DENIED_MESSAGE, wrongOwner.getMessage());
    }

    @Test
    void requireOwnedSession_ownerMissingOnLoadedEntity_failsClosed() {
        SessionEntity session = ownedSession("session-1", null, TENANT_ID);
        when(sessionRepository.findByIdAndUserIdAndTenantId("session-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.of(session));

        assertDenied(() -> service.requireOwnedSession("session-1", USER_ID, TENANT_ID));
    }

    @Test
    void requireOwnedSession_softDeletedResource_failsClosed() {
        SessionEntity session = ownedSession("session-1", USER_ID, TENANT_ID);
        session.setDeletedAt(LocalDateTime.now());
        when(sessionRepository.findByIdAndUserIdAndTenantId("session-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.of(session));

        assertDenied(() -> service.requireOwnedSession("session-1", USER_ID, TENANT_ID));
    }

    @Test
    void requireOwnedSession_missingContext_failsBeforeRepositoryLookup() {
        assertDenied(() -> service.requireOwnedSession("session-1", null, TENANT_ID));
        assertDenied(() -> service.requireOwnedSession(" ", USER_ID, TENANT_ID));

        verifyNoInteractions(sessionRepository, sessionTaskRepository);
    }

    @Test
    void requireOwnedTask_taskAndSessionHaveExactOwner_returnsTask() {
        SessionTaskEntity task = ownedTask("task-1", "session-1", USER_ID, TENANT_ID);
        SessionEntity session = ownedSession("session-1", USER_ID, TENANT_ID);
        when(sessionTaskRepository.findByTaskIdAndUserIdAndTenantId("task-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.of(task));
        when(sessionRepository.findByIdAndUserIdAndTenantId("session-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.of(session));

        SessionTaskEntity result = service.requireOwnedTask("task-1", USER_ID, TENANT_ID);

        assertSame(task, result);
    }

    @Test
    void requireOwnedTask_tenantlessTaskAndSessionWithSameUser_returnsTask() {
        SessionTaskEntity task = ownedTask("task-1", "session-1", USER_ID, null);
        SessionEntity session = ownedSession("session-1", USER_ID, null);
        when(sessionTaskRepository.findTenantlessByTaskIdAndUserId("task-1", USER_ID))
                .thenReturn(Optional.of(task));
        when(sessionRepository.findTenantlessByIdAndUserId("session-1", USER_ID))
                .thenReturn(Optional.of(session));

        SessionTaskEntity result = service.requireOwnedTask("task-1", USER_ID, null);

        assertSame(task, result);
    }

    @Test
    void requireOwnedTask_wrongTaskOwner_failsWithoutSessionLookup() {
        when(sessionTaskRepository.findByTaskIdAndUserIdAndTenantId("task-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        assertDenied(() -> service.requireOwnedTask("task-1", USER_ID, TENANT_ID));

        verifyNoInteractions(sessionRepository);
    }

    @Test
    void requireOwnedTask_loadedTaskWithMissingOrConflictingOwner_failsClosed() {
        SessionTaskEntity missingOwner = ownedTask("task-1", "session-1", USER_ID, null);
        SessionTaskEntity conflictingOwner = ownedTask("task-1", "session-1", "other-user", TENANT_ID);
        when(sessionTaskRepository.findByTaskIdAndUserIdAndTenantId("task-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.of(missingOwner))
                .thenReturn(Optional.of(conflictingOwner));

        assertDenied(() -> service.requireOwnedTask("task-1", USER_ID, TENANT_ID));
        assertDenied(() -> service.requireOwnedTask("task-1", USER_ID, TENANT_ID));
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void requireOwnedTask_associatedSessionMissingOrWrongOwner_failsGenerically() {
        SessionTaskEntity task = ownedTask("task-1", "session-1", USER_ID, TENANT_ID);
        when(sessionTaskRepository.findByTaskIdAndUserIdAndTenantId("task-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.of(task));
        when(sessionRepository.findByIdAndUserIdAndTenantId("session-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        assertDenied(() -> service.requireOwnedTask("task-1", USER_ID, TENANT_ID));
    }

    @Test
    void requireOwnedTask_loadedSessionWithConflictingOwner_failsClosed() {
        SessionTaskEntity task = ownedTask("task-1", "session-1", USER_ID, TENANT_ID);
        SessionEntity conflictingSession = ownedSession("session-1", USER_ID, "other-tenant");
        when(sessionTaskRepository.findByTaskIdAndUserIdAndTenantId("task-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.of(task));
        when(sessionRepository.findByIdAndUserIdAndTenantId("session-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.of(conflictingSession));

        assertDenied(() -> service.requireOwnedTask("task-1", USER_ID, TENANT_ID));
    }

    @Test
    void requireOwnedTask_associatedSessionDeleted_failsClosed() {
        SessionTaskEntity task = ownedTask("task-1", "session-1", USER_ID, TENANT_ID);
        SessionEntity session = ownedSession("session-1", USER_ID, TENANT_ID);
        session.setStatus("DELETED");
        when(sessionTaskRepository.findByTaskIdAndUserIdAndTenantId("task-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.of(task));
        when(sessionRepository.findByIdAndUserIdAndTenantId("session-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.of(session));

        assertDenied(() -> service.requireOwnedTask("task-1", USER_ID, TENANT_ID));
    }

    @Test
    void requireOwnedTask_missingSessionId_failsBeforeSessionLookup() {
        SessionTaskEntity task = ownedTask("task-1", null, USER_ID, TENANT_ID);
        when(sessionTaskRepository.findByTaskIdAndUserIdAndTenantId("task-1", USER_ID, TENANT_ID))
                .thenReturn(Optional.of(task));

        assertDenied(() -> service.requireOwnedTask("task-1", USER_ID, TENANT_ID));
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void requireOwnedTask_missingContext_failsBeforeRepositoryLookup() {
        assertDenied(() -> service.requireOwnedTask(null, USER_ID, TENANT_ID));
        assertDenied(() -> service.requireOwnedTask("task-1", "", TENANT_ID));

        verifyNoInteractions(sessionRepository, sessionTaskRepository);
    }

    @Test
    void requireTenantTask_sameTenantCrossOwnerReturnsDurableIdentity() {
        SessionTaskEntity task = managedTask(
                "task-managed", "session-managed", "durable-owner", TENANT_ID,
                "agent-managed");
        SessionEntity session = ownedSession(
                "session-managed", "durable-owner", TENANT_ID);
        when(sessionTaskRepository.findByTaskId("task-managed"))
                .thenReturn(Optional.of(task));
        when(sessionRepository.findByIdAndUserIdAndTenantId(
                "session-managed", "durable-owner", TENANT_ID))
                .thenReturn(Optional.of(session));

        SessionTaskResourceAccessService.ManagedTaskIdentity result =
                service.requireTenantTask("task-managed", TENANT_ID);

        assertEquals("task-managed", result.taskId());
        assertEquals("session-managed", result.sessionId());
        assertEquals("durable-owner", result.ownerUserId());
        assertEquals(TENANT_ID, result.tenantId());
        assertEquals("agent-managed", result.logicalAgentId());
        assertEquals("ManagedTaskIdentity[content-free]", result.toString());
    }

    @Test
    void requireTenantTask_crossTenantAndTenantlessFailBeforeSessionLookup() {
        when(sessionTaskRepository.findByTaskId("task-cross-tenant"))
                .thenReturn(Optional.of(managedTask(
                        "task-cross-tenant", "session-1", "owner-1",
                        "tenant-other", "agent-1")));

        assertDenied(() -> service.requireTenantTask(
                "task-cross-tenant", TENANT_ID));
        assertDenied(() -> service.requireTenantTask("task-tenantless", null));

        verifyNoInteractions(sessionRepository);
    }

    @Test
    void requireTenantTask_incompleteTaskAndSessionDriftFailClosed() {
        SessionTaskEntity incomplete = managedTask(
                "task-incomplete", "session-1", "owner-1", TENANT_ID, null);
        when(sessionTaskRepository.findByTaskId("task-incomplete"))
                .thenReturn(Optional.of(incomplete));
        assertDenied(() -> service.requireTenantTask("task-incomplete", TENANT_ID));

        SessionTaskEntity task = managedTask(
                "task-session-drift", "session-drift", "owner-1", TENANT_ID,
                "agent-1");
        SessionEntity drift = ownedSession(
                "session-drift", "owner-other", TENANT_ID);
        when(sessionTaskRepository.findByTaskId("task-session-drift"))
                .thenReturn(Optional.of(task));
        when(sessionRepository.findByIdAndUserIdAndTenantId(
                "session-drift", "owner-1", TENANT_ID))
                .thenReturn(Optional.of(drift));

        assertDenied(() -> service.requireTenantTask(
                "task-session-drift", TENANT_ID));
    }

    @Test
    void requireTenantTask_deletedSessionFailsClosed() {
        SessionTaskEntity task = managedTask(
                "task-deleted", "session-deleted", "owner-1", TENANT_ID,
                "agent-1");
        SessionEntity deleted = ownedSession(
                "session-deleted", "owner-1", TENANT_ID);
        deleted.setStatus("DELETED");
        when(sessionTaskRepository.findByTaskId("task-deleted"))
                .thenReturn(Optional.of(task));
        when(sessionRepository.findByIdAndUserIdAndTenantId(
                "session-deleted", "owner-1", TENANT_ID))
                .thenReturn(Optional.of(deleted));

        assertDenied(() -> service.requireTenantTask("task-deleted", TENANT_ID));
    }

    private static void assertDenied(Runnable invocation) {
        SecurityException exception = assertThrows(SecurityException.class, invocation::run);
        assertEquals(DENIED_MESSAGE, exception.getMessage());
    }

    private static SessionEntity ownedSession(String sessionId, String userId, String tenantId) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setTenantId(tenantId);
        return session;
    }

    private static SessionTaskEntity ownedTask(String taskId,
                                               String sessionId,
                                               String userId,
                                               String tenantId) {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId(sessionId);
        task.setUserId(userId);
        task.setTenantId(tenantId);
        return task;
    }

    private static SessionTaskEntity managedTask(
            String taskId,
            String sessionId,
            String userId,
            String tenantId,
            String agentId) {
        SessionTaskEntity task = ownedTask(taskId, sessionId, userId, tenantId);
        task.setAgentId(agentId);
        return task;
    }
}
