package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapApprovalActor;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NaviOperatorCredentialServiceTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void requireAdminOrOperatorAcceptsConfiguredOperatorKey() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("foggy.navigator.operator.api-key-hash"))
                .thenReturn(SecretTokenSupport.sha256("operator-key"));
        when(environment.getProperty("foggy.navigator.operator.credential-id")).thenReturn("operator-main");
        NaviOperatorCredentialService service = new NaviOperatorCredentialService(environment);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(NaviOperatorCredentialService.HEADER_OPERATOR_KEY, "operator-key");

        UpstreamBootstrapApprovalActor actor = service.requireAdminOrOperator(request);

        assertTrue(actor.isOperator());
        assertEquals("operator-main", actor.getOperatorCredentialId());
    }

    @Test
    void requireAdminOrOperatorAcceptsTenantAdminSession() {
        Environment environment = mock(Environment.class);
        NaviOperatorCredentialService service = new NaviOperatorCredentialService(environment);
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("admin-1")
                .tenantId("tenant-1")
                .roles("TENANT_ADMIN")
                .build());

        UpstreamBootstrapApprovalActor actor = service.requireAdminOrOperator(new MockHttpServletRequest());

        assertFalse(actor.isOperator());
        assertTrue(actor.isTenantAdmin());
        assertEquals("tenant-1", actor.getTenantId());
        assertEquals("admin-1", actor.getUserId());
    }

    @Test
    void requireAdminOrOperatorRejectsAnonymousWithoutValidOperatorKey() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("foggy.navigator.operator.api-key-hash"))
                .thenReturn(SecretTokenSupport.sha256("operator-key"));
        NaviOperatorCredentialService service = new NaviOperatorCredentialService(environment);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(NaviOperatorCredentialService.HEADER_OPERATOR_KEY, "wrong-key");

        assertThrows(SecurityException.class, () -> service.requireAdminOrOperator(request));
    }
}
