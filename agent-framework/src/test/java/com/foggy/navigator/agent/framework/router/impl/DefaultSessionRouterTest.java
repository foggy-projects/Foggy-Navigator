package com.foggy.navigator.agent.framework.router.impl;

import com.foggy.navigator.agent.framework.core.AgentInfo;
import com.foggy.navigator.agent.framework.core.AgentRegistry;
import com.foggy.navigator.agent.framework.core.AgentStatus;
import com.foggy.navigator.agent.framework.protocol.route.RouteAction;
import com.foggy.navigator.agent.framework.router.DelegationRequest;
import com.foggy.navigator.agent.framework.router.DelegationResult;
import com.foggy.navigator.agent.framework.router.PreconditionCheckResult;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.agent.framework.session.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultSessionRouterTest {

    @Mock
    private SessionManager sessionManager;

    @Mock
    private AgentRegistry agentRegistry;

    private DefaultSessionRouter router;

    @BeforeEach
    void setUp() {
        router = new DefaultSessionRouter(sessionManager, agentRegistry);
    }

    @Test
    void delegateToAgent_shouldCreateNewSessionAndReturnRoute() {
        AgentInfo targetAgent = AgentInfo.builder()
                .id("target-agent")
                .name("Target Agent")
                .status(AgentStatus.ACTIVE)
                .build();
        when(agentRegistry.findById("target-agent")).thenReturn(targetAgent);
        when(sessionManager.createSession(any())).thenReturn("new-session-id");

        DelegationRequest request = DelegationRequest.builder()
                .sourceSessionId("source-session")
                .targetAgentId("target-agent")
                .userId("user-1")
                .tenantId("tenant-1")
                .intent("Help with coding")
                .parameters(Map.of("language", "java"))
                .build();

        DelegationResult result = router.delegateToAgent(request);

        assertTrue(result.isSuccess());
        assertEquals("new-session-id", result.getNewSessionId());
        assertNotNull(result.getRoute());
        assertEquals(RouteAction.DELEGATE, result.getRoute().getAction());
        assertEquals("target-agent", result.getRoute().getTarget().getAgentId());
        assertEquals("Target Agent", result.getRoute().getTarget().getAgentName());

        verify(sessionManager).updateStatus("source-session", SessionStatus.DELEGATED);
        verify(sessionManager).createSession(any(SessionCreateRequest.class));
    }

    @Test
    void delegateToAgent_shouldReturnErrorForUnknownAgent() {
        when(agentRegistry.findById("unknown")).thenReturn(null);

        DelegationRequest request = DelegationRequest.builder()
                .targetAgentId("unknown")
                .userId("user-1")
                .build();

        DelegationResult result = router.delegateToAgent(request);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Target agent not found"));
    }

    @Test
    void returnToParent_shouldCloseCurrentAndActivateParent() {
        Session currentSession = Session.builder()
                .id("child-session")
                .parentSessionId("parent-session")
                .agentId("child-agent")
                .build();
        Session parentSession = Session.builder()
                .id("parent-session")
                .agentId("parent-agent")
                .status(SessionStatus.DELEGATED)
                .build();
        AgentInfo parentAgent = AgentInfo.builder()
                .id("parent-agent")
                .name("Parent Agent")
                .build();

        when(sessionManager.getSession("child-session")).thenReturn(currentSession);
        when(sessionManager.getSession("parent-session")).thenReturn(parentSession);
        when(agentRegistry.findById("parent-agent")).thenReturn(parentAgent);

        DelegationResult result = router.returnToParent("child-session");

        assertTrue(result.isSuccess());
        assertEquals("parent-session", result.getNewSessionId());
        assertEquals(RouteAction.RETURN, result.getRoute().getAction());
        assertEquals("parent-agent", result.getRoute().getTarget().getAgentId());

        verify(sessionManager).closeSession("child-session");
        verify(sessionManager).updateStatus("parent-session", SessionStatus.ACTIVE);
    }

    @Test
    void returnToParent_shouldReturnErrorForNonExistentSession() {
        when(sessionManager.getSession("non-existent")).thenReturn(null);

        DelegationResult result = router.returnToParent("non-existent");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Session not found"));
    }

    @Test
    void returnToParent_shouldReturnErrorForNoParent() {
        Session currentSession = Session.builder()
                .id("root-session")
                .parentSessionId(null)
                .build();
        when(sessionManager.getSession("root-session")).thenReturn(currentSession);

        DelegationResult result = router.returnToParent("root-session");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("No parent session"));
    }

    @Test
    void checkPreconditions_shouldReturnSatisfiedForEmptyConditions() {
        PreconditionCheckResult result = router.checkPreconditions(null);
        assertTrue(result.isSatisfied());

        result = router.checkPreconditions(Map.of());
        assertTrue(result.isSatisfied());
    }

    @Test
    void checkPreconditions_shouldReturnSatisfiedWhenAllTrue() {
        Map<String, Object> conditions = Map.of(
                "logged_in", true,
                "has_permissions", true
        );

        PreconditionCheckResult result = router.checkPreconditions(conditions);

        assertTrue(result.isSatisfied());
    }

    @Test
    void checkPreconditions_shouldReturnNotSatisfiedWhenFalse() {
        Map<String, Object> conditions = Map.of(
                "logged_in", true,
                "has_permissions", false,
                "verified_email", false
        );

        PreconditionCheckResult result = router.checkPreconditions(conditions);

        assertFalse(result.isSatisfied());
        assertNotNull(result.getMissedConditions());
        assertEquals(2, result.getMissedConditions().size());
        assertTrue(result.getMissedConditions().contains("has_permissions"));
        assertTrue(result.getMissedConditions().contains("verified_email"));
    }
}
