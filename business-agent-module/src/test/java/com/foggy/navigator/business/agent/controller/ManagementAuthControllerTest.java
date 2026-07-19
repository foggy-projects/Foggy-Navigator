package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ManagementAuthorizationExplainResponseDTO;
import com.foggy.navigator.business.agent.model.dto.ManagementIssuedTokenResponseDTO;
import com.foggy.navigator.business.agent.model.dto.ManagementPermissionsResponseDTO;
import com.foggy.navigator.business.agent.model.dto.ManagementWhoamiResponseDTO;
import com.foggy.navigator.business.agent.model.form.ManagementAuthorizationExplainForm;
import com.foggy.navigator.business.agent.model.form.ManagementSecurityActionAuthorizeForm;
import com.foggy.navigator.business.agent.service.ManagementAuthEndpointService;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationDecisionOutcome;
import com.foggy.navigator.common.authorization.AuthorizationEvaluationMode;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.authorization.IssuedManagementToken;
import com.foggy.navigator.common.authorization.ManagementActionSetRegistry;
import com.foggy.navigator.common.authorization.ManagementAuthenticationContext;
import com.foggy.navigator.common.authorization.ManagementAuthenticationInspection;
import com.foggy.navigator.common.authorization.ManagementAuthorizationExplanation;
import com.foggy.navigator.common.authorization.ManagementTokenPurpose;
import com.foggy.navigator.common.authorization.PolicyDecisionV1;
import com.foggy.navigator.common.authorization.TypedManagementCredentialSource;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManagementAuthControllerTest {

    @Test
    void declaresExactlyTheFiveTypedManagementMappingsWithoutLegacyRequireAuth() {
        RequestMapping root = ManagementAuthController.class.getAnnotation(RequestMapping.class);
        assertEquals(ManagementAuthEndpointService.BASE_PATH, root.value()[0]);
        Map<String, String> mappings = Arrays.stream(ManagementAuthController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class) || method.isAnnotationPresent(GetMapping.class))
                .collect(java.util.stream.Collectors.toMap(Method::getName, this::mapping));

        assertEquals(Map.of(
                "exchange", "POST /exchange",
                "authorizeSecurityAction", "POST /security-actions/authorize",
                "whoami", "GET /whoami",
                "permissions", "GET /permissions",
                "explain", "POST /explain"), mappings);
        assertFalse(Arrays.stream(ManagementAuthController.class.getAnnotations())
                .anyMatch(annotation -> annotation.annotationType().getSimpleName().equals("RequireAuth")));
        assertFalse(Arrays.stream(ManagementAuthController.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getAnnotations()))
                .anyMatch(annotation -> annotation.annotationType().getSimpleName().equals("RequireAuth")));
    }

    @Test
    void missingSafeContextRejectsBeforeAnyEndpointServiceCall() {
        ManagementAuthEndpointService service = mock(ManagementAuthEndpointService.class);
        ManagementAuthController controller = new ManagementAuthController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThrows(SecurityException.class, () -> controller.exchange(request));

        verifyNoInteractions(service);
    }

    @Test
    void controllerForwardsOnlySafeContextAndReturnsRxOkForEachEndpoint() {
        ManagementAuthEndpointService service = mock(ManagementAuthEndpointService.class);
        ManagementAuthController controller = new ManagementAuthController(service);

        ManagementAuthenticationContext exchangeContext = context(
                ManagementAuthEndpointService.EXCHANGE_ROUTE_ID, ManagementAuthEndpointService.EXCHANGE_ACTION);
        MockHttpServletRequest exchangeRequest = request(exchangeContext);
        ManagementIssuedTokenResponseDTO exchangeResponse = tokenResponse(ManagementTokenPurpose.CONTROL_ACCESS);
        when(service.exchange(same(exchangeContext))).thenReturn(exchangeResponse);
        RX<ManagementIssuedTokenResponseDTO> exchange = controller.exchange(exchangeRequest);
        assertEquals(RX.SUCCESS, exchange.getCode());
        assertSame(exchangeResponse, exchange.getData());
        verify(service).exchange(exchangeContext);

        ManagementAuthenticationContext securityContext = context(
                ManagementAuthEndpointService.SECURITY_AUTHORIZE_ROUTE_ID,
                ManagementAuthEndpointService.SECURITY_AUTHORIZE_ACTION);
        ManagementSecurityActionAuthorizeForm securityForm = new ManagementSecurityActionAuthorizeForm();
        when(service.authorizeSecurityAction(same(securityContext), same(securityForm)))
                .thenReturn(tokenResponse(ManagementTokenPurpose.SECURITY_ACTION));
        assertEquals(RX.SUCCESS, controller.authorizeSecurityAction(request(securityContext), securityForm).getCode());
        verify(service).authorizeSecurityAction(securityContext, securityForm);

        ManagementAuthenticationContext whoamiContext = context(
                ManagementAuthEndpointService.WHOAMI_ROUTE_ID, ManagementAuthEndpointService.WHOAMI_ACTION);
        ManagementWhoamiResponseDTO whoamiResponse = ManagementWhoamiResponseDTO.from(
                new ManagementAuthenticationInspection(whoamiContext, Set.of("instance.configure"), Set.of("auth.whoami")));
        when(service.whoami(same(whoamiContext))).thenReturn(whoamiResponse);
        assertSame(whoamiResponse, controller.whoami(request(whoamiContext)).getData());
        verify(service).whoami(whoamiContext);

        ManagementAuthenticationContext permissionsContext = context(
                ManagementAuthEndpointService.PERMISSIONS_ROUTE_ID, ManagementAuthEndpointService.PERMISSIONS_ACTION);
        ManagementPermissionsResponseDTO permissionsResponse = ManagementPermissionsResponseDTO.from(
                new ManagementAuthenticationInspection(permissionsContext, Set.of("instance.configure"),
                        Set.of("auth.permissions.inspect")));
        when(service.permissions(same(permissionsContext))).thenReturn(permissionsResponse);
        assertSame(permissionsResponse, controller.permissions(request(permissionsContext)).getData());
        verify(service).permissions(permissionsContext);

        ManagementAuthenticationContext explainContext = context(
                ManagementAuthEndpointService.EXPLAIN_ROUTE_ID, ManagementAuthEndpointService.EXPLAIN_ACTION);
        ManagementAuthorizationExplainForm explainForm = new ManagementAuthorizationExplainForm();
        ManagementAuthorizationExplainResponseDTO explainResponse = ManagementAuthorizationExplainResponseDTO.from(
                new ManagementAuthorizationExplanation(true, null, true, new PolicyDecisionV1(
                        "navi.authorization.v1", "policy-v1", "catalog-v1", "test-build",
                        "decision-id", "correlation-id", AuthorizationEvaluationMode.PREFLIGHT,
                        AuthorizationDecisionOutcome.ALLOW, null, true,
                        ManagementAuthEndpointService.EXPLAIN_ACTION,
                        ManagementAuthEndpointService.EXPLAIN_ROUTE_ID, Instant.now())));
        when(service.explain(same(explainContext), same(explainForm))).thenReturn(explainResponse);
        assertSame(explainResponse, controller.explain(request(explainContext), explainForm).getData());
        verify(service).explain(explainContext, explainForm);
    }

    private String mapping(Method method) {
        if (method.isAnnotationPresent(PostMapping.class)) {
            return "POST " + method.getAnnotation(PostMapping.class).value()[0];
        }
        return "GET " + method.getAnnotation(GetMapping.class).value()[0];
    }

    private MockHttpServletRequest request(ManagementAuthenticationContext context) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ManagementAuthenticationContext.class.getName(), context);
        return request;
    }

    private ManagementIssuedTokenResponseDTO tokenResponse(ManagementTokenPurpose purpose) {
        return ManagementIssuedTokenResponseDTO.from(new IssuedManagementToken(
                "issued-token", "token-id", "token-reference", purpose, Instant.now().plusSeconds(60)));
    }

    private ManagementAuthenticationContext context(String routeId, String actionId) {
        return new ManagementAuthenticationContext(
                "principal-record-id",
                AuthorizationPrincipalType.INSTANCE_ROOT,
                "principal-id",
                "source-upstream-system-id",
                "navigator-instance-id",
                "internal-dev",
                "S1_INSTANCE_ROOT",
                "credential-id",
                AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
                "credential-fingerprint",
                1,
                ManagementActionSetRegistry.INSTANCE_ROOT_CONTROL_V1,
                "ACTIVE",
                Instant.parse("2030-01-01T00:00:00Z"),
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null,
                routeId,
                actionId,
                "correlation-id");
    }
}
