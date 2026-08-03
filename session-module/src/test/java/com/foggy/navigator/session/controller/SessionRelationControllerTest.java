package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.session.dto.SessionForwardCreateRequest;
import com.foggy.navigator.session.dto.SessionForwardCreateResponse;
import com.foggy.navigator.session.dto.SessionRelationDTO;
import com.foggy.navigator.session.service.SessionForwardService;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionRelationControllerTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String CLIENT_REQUEST_ID =
            "5f5a402e-8506-4735-8441-1cbaca240627";

    @Mock
    private SessionForwardService sessionForwardService;

    private SessionRelationController controller;

    @BeforeEach
    void setUp() {
        controller = new SessionRelationController(sessionForwardService);
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void forwardPassesHeaderBodyAndAuthenticatedOwnerExactlyToFourArgumentService() {
        SessionForwardCreateRequest request = request("NEW_SESSION");
        SessionForwardCreateResponse response = response("NEW_SESSION");
        when(sessionForwardService.forwardToNewSession(
                same(request), eq(USER_ID), eq(TENANT_ID), eq(CLIENT_REQUEST_ID)))
                .thenReturn(response);

        RX<SessionForwardCreateResponse> result =
                controller.forwardToNewSession(request, CLIENT_REQUEST_ID);

        assertSame(response, result.getData());
        verify(sessionForwardService).forwardToNewSession(
                same(request), eq(USER_ID), eq(TENANT_ID), eq(CLIENT_REQUEST_ID));
    }

    @Test
    void forwardMintsOnlyWhenAbsentRejectsBlankAndPassesMalformedForCanonicalValidation() {
        SessionForwardCreateRequest absent = request("NEW_SESSION");
        SessionForwardCreateRequest blank = request("NEW_SESSION");
        SessionForwardCreateRequest malformed = request("NEW_SESSION");
        when(sessionForwardService.forwardToNewSession(
                same(absent), eq(USER_ID), eq(TENANT_ID), isNull()))
                .thenReturn(response("NEW_SESSION"));
        when(sessionForwardService.forwardToNewSession(
                same(malformed), eq(USER_ID), eq(TENANT_ID), eq("not-a-uuid")))
                .thenReturn(response("NEW_SESSION"));

        controller.forwardToNewSession(absent, null);
        IllegalArgumentException blankFailure = assertThrows(
                IllegalArgumentException.class,
                () -> controller.forwardToNewSession(blank, " "));
        controller.forwardToNewSession(malformed, "not-a-uuid");

        assertEquals("X_NAVIGATOR_CLIENT_REQUEST_ID_BLANK",
                blankFailure.getMessage());
        verify(sessionForwardService).forwardToNewSession(
                same(absent), eq(USER_ID), eq(TENANT_ID), isNull());
        verify(sessionForwardService, never()).forwardToNewSession(
                same(blank), eq(USER_ID), eq(TENANT_ID), eq(" "));
        verify(sessionForwardService).forwardToNewSession(
                same(malformed), eq(USER_ID), eq(TENANT_ID), eq("not-a-uuid"));
    }

    @Test
    void existingSessionStillDelegatesWithoutControllerModeBranching() {
        SessionForwardCreateRequest request = request("EXISTING_SESSION");
        SessionForwardCreateResponse response = response("EXISTING_SESSION");
        when(sessionForwardService.forwardToNewSession(
                same(request), eq(USER_ID), eq(TENANT_ID), eq(CLIENT_REQUEST_ID)))
                .thenReturn(response);

        RX<SessionForwardCreateResponse> result =
                controller.forwardToNewSession(request, CLIENT_REQUEST_ID);

        assertSame(response, result.getData());
        assertEquals("EXISTING_SESSION", result.getData().getTargetMode());
        verify(sessionForwardService).forwardToNewSession(
                same(request), eq(USER_ID), eq(TENANT_ID), eq(CLIENT_REQUEST_ID));
    }

    @Test
    void incomingForwardReadContractIsUnchanged() {
        SessionRelationDTO relation = SessionRelationDTO.builder().id(19L).build();
        when(sessionForwardService.findIncomingForwardRelation(
                "session-target", USER_ID, TENANT_ID)).thenReturn(relation);

        RX<SessionRelationDTO> result =
                controller.findIncomingForwardRelation("session-target");

        assertSame(relation, result.getData());
        verify(sessionForwardService).findIncomingForwardRelation(
                "session-target", USER_ID, TENANT_ID);
    }

    @Test
    void freezesAuthenticatedRouteAndOptionalHeaderCarrier() throws Exception {
        assertTrue(SessionRelationController.class.isAnnotationPresent(RequireAuth.class));
        RequestMapping root = SessionRelationController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/v1/session-relations"}, root.value());

        Method method = SessionRelationController.class.getDeclaredMethod(
                "forwardToNewSession", SessionForwardCreateRequest.class, String.class);
        PostMapping post = method.getAnnotation(PostMapping.class);
        assertArrayEquals(new String[]{"/forward"}, post.value());
        RequestHeader header = method.getParameters()[1].getAnnotation(RequestHeader.class);
        assertEquals("X-Navigator-Client-Request-Id", header.value());
        assertFalse(header.required());
        verify(sessionForwardService, times(0)).forwardToNewSession(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static SessionForwardCreateRequest request(String targetMode) {
        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-source");
        request.setSourceMessageId("message-source");
        request.setTargetMode(targetMode);
        return request;
    }

    private static SessionForwardCreateResponse response(String targetMode) {
        return SessionForwardCreateResponse.builder()
                .relationId(17L)
                .targetMode(targetMode)
                .sourceSessionId("session-source")
                .sourceMessageId("message-source")
                .targetSessionId("session-target")
                .build();
    }
}
