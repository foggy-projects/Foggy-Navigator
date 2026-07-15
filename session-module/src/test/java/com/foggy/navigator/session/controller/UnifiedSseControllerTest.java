package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.session.service.SessionTaskResourceAccessService;
import com.foggy.navigator.session.sse.UnifiedSseEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class UnifiedSseControllerTest {

    private UnifiedSseEmitter unifiedSseEmitter;
    private SessionTaskResourceAccessService resourceAccessService;
    private UnifiedSseController controller;

    @BeforeEach
    void setUp() {
        unifiedSseEmitter = mock(UnifiedSseEmitter.class);
        resourceAccessService = mock(SessionTaskResourceAccessService.class);
        controller = new UnifiedSseController(unifiedSseEmitter, resourceAccessService);

        // Set up user context
        CurrentUser user = new CurrentUser();
        user.setUserId("user1");
        user.setUsername("testuser");
        user.setTenantId("tenant1");
        UserContext.setCurrentUser(user);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void subscribe_validSession_subscribes() {
        SessionEntity session = new SessionEntity();
        session.setId("session1");
        when(resourceAccessService.requireOwnedSession("session1", "user1", "tenant1"))
                .thenReturn(session);

        UnifiedSseController.SubscribeForm form = new UnifiedSseController.SubscribeForm();
        form.setSessionIds(List.of("session1"));

        controller.subscribe(form);

        verify(resourceAccessService).requireOwnedSession("session1", "user1", "tenant1");
        verify(unifiedSseEmitter).subscribe("user1", "session1");
    }

    @Test
    void subscribe_sessionNotOwned_failsClosed() {
        when(resourceAccessService.requireOwnedSession("session1", "user1", "tenant1"))
                .thenThrow(new SecurityException("Session not found or not owned"));

        UnifiedSseController.SubscribeForm form = new UnifiedSseController.SubscribeForm();
        form.setSessionIds(List.of("session1"));

        assertThrows(SecurityException.class, () -> controller.subscribe(form));

        verify(unifiedSseEmitter, never()).subscribe(anyString(), anyString());
    }

    @Test
    void subscribe_sessionNotFound_failsClosed() {
        when(resourceAccessService.requireOwnedSession("session1", "user1", "tenant1"))
                .thenThrow(new SecurityException("Session not found or not owned"));

        UnifiedSseController.SubscribeForm form = new UnifiedSseController.SubscribeForm();
        form.setSessionIds(List.of("session1"));

        assertThrows(SecurityException.class, () -> controller.subscribe(form));

        verify(unifiedSseEmitter, never()).subscribe(anyString(), anyString());
    }

    @Test
    void subscribe_multipleSessions_validatesAllBeforeSubscribing() {
        SessionEntity session = new SessionEntity();
        session.setId("session1");
        when(resourceAccessService.requireOwnedSession("session1", "user1", "tenant1"))
                .thenReturn(session);
        when(resourceAccessService.requireOwnedSession("session2", "user1", "tenant1"))
                .thenThrow(new SecurityException("Session not found or not owned"));

        UnifiedSseController.SubscribeForm form = new UnifiedSseController.SubscribeForm();
        form.setSessionIds(List.of("session1", "session2"));

        assertThrows(SecurityException.class, () -> controller.subscribe(form));

        verify(unifiedSseEmitter, never()).subscribe(anyString(), anyString());
    }

    @Test
    void unsubscribe_callsEmitter() {
        UnifiedSseController.SubscribeForm form = new UnifiedSseController.SubscribeForm();
        form.setSessionIds(List.of("session1", "session2"));

        controller.unsubscribe(form);

        verify(unifiedSseEmitter).unsubscribe("user1", "session1");
        verify(unifiedSseEmitter).unsubscribe("user1", "session2");
    }

    @Test
    void subscribe_emptyList_noOp() {
        UnifiedSseController.SubscribeForm form = new UnifiedSseController.SubscribeForm();
        form.setSessionIds(List.of());

        controller.subscribe(form);

        verify(unifiedSseEmitter, never()).subscribe(anyString(), anyString());
    }
}
