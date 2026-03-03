package com.foggy.navigator.session.controller;

import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.session.sse.UnifiedSseEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class UnifiedSseControllerTest {

    private UnifiedSseEmitter unifiedSseEmitter;
    private SessionManager sessionManager;
    private UnifiedSseController controller;

    @BeforeEach
    void setUp() {
        unifiedSseEmitter = mock(UnifiedSseEmitter.class);
        sessionManager = mock(SessionManager.class);
        controller = new UnifiedSseController(unifiedSseEmitter, sessionManager);

        // Set up user context
        CurrentUser user = new CurrentUser();
        user.setUserId("user1");
        user.setUsername("testuser");
        UserContext.setCurrentUser(user);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void subscribe_validSession_subscribes() {
        Session session = Session.builder().id("session1").userId("user1").build();
        when(sessionManager.getSession("session1")).thenReturn(session);

        UnifiedSseController.SubscribeForm form = new UnifiedSseController.SubscribeForm();
        form.setSessionIds(List.of("session1"));

        controller.subscribe(form);

        verify(unifiedSseEmitter).subscribe("user1", "session1");
    }

    @Test
    void subscribe_sessionNotOwned_skips() {
        Session session = Session.builder().id("session1").userId("other-user").build();
        when(sessionManager.getSession("session1")).thenReturn(session);

        UnifiedSseController.SubscribeForm form = new UnifiedSseController.SubscribeForm();
        form.setSessionIds(List.of("session1"));

        controller.subscribe(form);

        verify(unifiedSseEmitter, never()).subscribe(anyString(), anyString());
    }

    @Test
    void subscribe_sessionNotFound_skips() {
        when(sessionManager.getSession("session1")).thenReturn(null);

        UnifiedSseController.SubscribeForm form = new UnifiedSseController.SubscribeForm();
        form.setSessionIds(List.of("session1"));

        controller.subscribe(form);

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
