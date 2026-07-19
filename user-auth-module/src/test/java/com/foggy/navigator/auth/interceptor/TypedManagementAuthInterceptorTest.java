package com.foggy.navigator.auth.interceptor;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationDecisionOutcome;
import com.foggy.navigator.common.authorization.AuthorizationEvaluationMode;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.authorization.AuthorizationReasonCode;
import com.foggy.navigator.common.authorization.AuthorizationRequiredSection;
import com.foggy.navigator.common.authorization.AuthorizationRouteCatalog;
import com.foggy.navigator.common.authorization.AuthorizationRouteManifestEntry;
import com.foggy.navigator.common.authorization.AuthorizationSchemaV1;
import com.foggy.navigator.common.authorization.ManagementActionSetRegistry;
import com.foggy.navigator.common.authorization.ManagementAuthenticationContext;
import com.foggy.navigator.common.authorization.PolicyDecisionV1;
import com.foggy.navigator.common.authorization.TypedManagementAuthenticationRequest;
import com.foggy.navigator.common.authorization.TypedManagementAuthorizationResult;
import com.foggy.navigator.common.authorization.TypedManagementCredentialSource;
import com.foggy.navigator.common.authorization.TypedManagementIngressAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TypedManagementAuthInterceptorTest {

    private static final String EXCHANGE_PATH = "/api/v1/management/v1/auth/exchange";
    private static final String EXCHANGE_ROUTE = "mvc:post:" + EXCHANGE_PATH;
    private static final String PRINCIPAL_CREDENTIAL = "navi-pc1.test-reference.test-secret-material";
    private static final String MANAGEMENT_BEARER = "navi-mt1.mt.test-reference.test-secret-material";
    private static final String AUTH_CONTEXT_ATTRIBUTE = ManagementAuthenticationContext.class.getName();

    @Mock
    private AuthorizationRouteCatalog routeCatalog;

    @Mock
    private ObjectProvider<TypedManagementIngressAuthorizer> authorizerProvider;

    @Mock
    private TypedManagementIngressAuthorizer authorizer;

    private TypedManagementAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new TypedManagementAuthInterceptor(
                routeCatalog, new ManagementActionSetRegistry(), authorizerProvider);
    }

    @Test
    void rejectsUnregisteredPathBeforeResolvingAnyCredential() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/v1/management/v1/auth/not-registered");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 403, AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
        verifyNoInteractions(routeCatalog, authorizerProvider, authorizer);
    }

    @ParameterizedTest(name = "unregistered management namespace ingress {0} {1} fails before credential handling")
    @MethodSource("unregisteredManagementNamespaceIngresses")
    void rejectsEveryUnsupportedManagementNamespaceMethodOrPathBeforeCredentialHandling(
            String method,
            String path
    ) throws Exception {
        MockHttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 403, AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
        verifyNoInteractions(routeCatalog, authorizerProvider, authorizer);
    }

    @Test
    void rejectsRouteWhenCatalogLookupFailsClosed() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(routeCatalog.findByDeploymentMethodAndPath(
                AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER, "POST", EXCHANGE_PATH))
                .thenThrow(new IllegalStateException("catalog unavailable"));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 403, AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
        verifyNoInteractions(authorizerProvider, authorizer);
    }

    @Test
    void rejectsRouteOutsideCanonicalEnforcementModeBeforeProviderLookup() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubRoute(route("auth.exchange", "TYPED_MANAGEMENT_AUTH", "SHADOW"));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 403, AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
        verifyNoInteractions(authorizerProvider, authorizer);
    }

    @Test
    void rejectsRouteOutsideTypedManagementSurfaceBeforeProviderLookup() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubRoute(route("auth.exchange", "OPEN_API", "CANONICAL_ENFORCE"));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 403, AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
        verifyNoInteractions(authorizerProvider, authorizer);
    }

    @Test
    void rejectsRouteActionPairThatIsNotFixedByTheActionSetRegistry() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubRoute(route("auth.security-authorize", "TYPED_MANAGEMENT_AUTH", "CANONICAL_ENFORCE"));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 403, AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
        verifyNoInteractions(authorizerProvider, authorizer);
    }

    @Test
    void rejectsCatalogEntryWhoseRouteIdDoesNotBelongToTheRequestPath() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthorizationRouteManifestEntry wrongRouteId = new AuthorizationRouteManifestEntry(
                "mvc:get:/api/v1/management/v1/auth/whoami",
                AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER,
                "POST",
                EXCHANGE_PATH,
                "TYPED_MANAGEMENT_AUTH",
                "TypedManagementAuthController#exchange",
                "user-auth-module",
                "typed-management",
                "server-derived",
                "auth.exchange",
                "INSTANCE_ROOT_CONTROL",
                "typed-management",
                "HIGH",
                "CANONICAL_ENFORCE",
                "ENFORCE",
                "APPROVED",
                "test-only",
                Set.of(AuthorizationRequiredSection.PRINCIPAL));
        stubRoute(wrongRouteId);

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 403, AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
        verifyNoInteractions(authorizerProvider, authorizer);
    }

    @Test
    void failsClosedWhenNoUniqueTypedManagementAuthorizerIsAvailable() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubRoute(route("auth.exchange", "TYPED_MANAGEMENT_AUTH", "CANONICAL_ENFORCE"));
        when(authorizerProvider.getIfUnique()).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        verifyNoInteractions(authorizer);
    }

    @Test
    void failsClosedWhenProviderLookupThrows() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubRoute(route("auth.exchange", "TYPED_MANAGEMENT_AUTH", "CANONICAL_ENFORCE"));
        when(authorizerProvider.getIfUnique()).thenThrow(new IllegalStateException("multiple providers"));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        verifyNoInteractions(authorizer);
    }

    @Test
    void failsClosedWhenCanonicalResolverThrows() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenThrow(new IllegalStateException("verifier unavailable"));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        assertNull(request.getAttribute(AUTH_CONTEXT_ATTRIBUTE));
    }

    @Test
    void forwardsCanonicalDenyWithoutPublishingAnAuthenticationContext() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        request.setAttribute(AUTH_CONTEXT_ATTRIBUTE, context());
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(denied(AuthorizationReasonCode.AUTHZ_ACTION_DENIED));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 403, AuthorizationReasonCode.AUTHZ_ACTION_DENIED);
        assertNull(request.getAttribute(AUTH_CONTEXT_ATTRIBUTE));
    }

    @Test
    void forwardsMissingCredentialAsTheStableCanonicalFailure() throws Exception {
        MockHttpServletRequest request = request("POST", EXCHANGE_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_MISSING));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        TypedManagementAuthenticationRequest captured = capturedRequest();
        assertNull(captured.principalCredential());
        assertNull(captured.managementBearer());
        assertFalse(captured.prohibitedCredentialSourcePresent());
        assertFalse(captured.malformedTypedCredentialPresentation());
        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_MISSING);
    }

    @Test
    void acceptsOnlyTheSafeContextReturnedByTheCanonicalFacade() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ManagementAuthenticationContext context = context();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(allowed(context));

        assertTrue(interceptor.preHandle(request, response, new Object()));

        ArgumentCaptor<TypedManagementAuthenticationRequest> requestCaptor =
                ArgumentCaptor.forClass(TypedManagementAuthenticationRequest.class);
        verify(authorizer).authorize(requestCaptor.capture());
        TypedManagementAuthenticationRequest captured = requestCaptor.getValue();
        assertEquals(EXCHANGE_ROUTE, captured.routeId());
        assertEquals("auth.exchange", captured.actionId());
        assertNotNull(captured.principalCredential());
        assertNull(captured.managementBearer());
        assertFalse(captured.prohibitedCredentialSourcePresent());
        assertFalse(captured.toString().contains(PRINCIPAL_CREDENTIAL));
        assertSame(context, request.getAttribute(AUTH_CONTEXT_ATTRIBUTE));
        assertFalse(response.getContentAsString().contains(PRINCIPAL_CREDENTIAL));
    }

    @Test
    void acceptsOnlyAManagementBearerResultBoundToTheBearerIngressCarrier() throws Exception {
        MockHttpServletRequest request = bearerRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ManagementAuthenticationContext context = contextWithCredentialSource(
                TypedManagementCredentialSource.MANAGEMENT_BEARER, "test-correlation-id");
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(allowed(context));

        assertTrue(interceptor.preHandle(request, response, new Object()));

        assertSame(context, request.getAttribute(AUTH_CONTEXT_ATTRIBUTE));
    }

    @ParameterizedTest(name = "allowed result with {0} decision fails closed")
    @MethodSource("invalidAllowedDecisions")
    void rejectsAllowedResultWithoutABoundCanonicalEnforcementDecision(
            String ignoredDescription,
            PolicyDecisionV1 decision
    ) throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ManagementAuthenticationContext context = context();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(allowed(context, decision));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        assertNull(request.getAttribute(AUTH_CONTEXT_ATTRIBUTE));
    }

    @Test
    void rejectsAllowedResultWhoseContextAndDecisionAreNotBoundToThisRequestCorrelation() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ManagementAuthenticationContext staleContext = contextWithCorrelation("different-correlation-id");
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(allowed(staleContext));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        assertNull(request.getAttribute(AUTH_CONTEXT_ATTRIBUTE));
    }

    @Test
    void rejectsAllowedResultWhoseDeclaredCredentialSourceDiffersFromItsContext() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ManagementAuthenticationContext context = context();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(new TypedManagementAuthorizationResult(
                true, null, TypedManagementCredentialSource.MANAGEMENT_BEARER, context, enforcementDecision(context)));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        assertNull(request.getAttribute(AUTH_CONTEXT_ATTRIBUTE));
    }

    @Test
    void rejectsAllowedResultWhoseCredentialSourceDoesNotMatchTheIngressCarrier() throws Exception {
        MockHttpServletRequest request = bearerRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ManagementAuthenticationContext principalContext = context();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(allowed(principalContext));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        assertNull(request.getAttribute(AUTH_CONTEXT_ATTRIBUTE));
    }

    @Test
    void rejectsFacadeAllowWhenAProhibitedCredentialCarrierWasAlsoPresented() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        request.addHeader("X-API-Key", "not-a-typed-management-credential");
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(allowed(context()));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        assertNull(request.getAttribute(AUTH_CONTEXT_ATTRIBUTE));
    }

    @Test
    void rejectsFacadeAllowWhenTheTypedCredentialPresentationWasMalformed() throws Exception {
        MockHttpServletRequest request = request("POST", EXCHANGE_PATH);
        request.setAttribute(TypedManagementAuthInterceptor.class.getName() + ".correlationId", "test-correlation-id");
        request.addHeader("X-Navi-Principal-Credential", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(allowed(context()));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        assertNull(request.getAttribute(AUTH_CONTEXT_ATTRIBUTE));
    }

    @Test
    void marksPrincipalAndBearerAsAConflictForTheCanonicalFacade() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        request.addHeader("Authorization", "Bearer " + MANAGEMENT_BEARER);
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        TypedManagementAuthenticationRequest captured = capturedRequest();
        assertNotNull(captured.principalCredential());
        assertNotNull(captured.managementBearer());
        assertFalse(captured.prohibitedCredentialSourcePresent());
        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT);
    }

    @Test
    void rejectsMalformedAuthorizationWithoutPassingItsMaterialAsABearer() throws Exception {
        MockHttpServletRequest request = request("POST", EXCHANGE_PATH);
        request.addHeader("Authorization", "Basic " + MANAGEMENT_BEARER);
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        TypedManagementAuthenticationRequest captured = capturedRequest();
        assertNull(captured.principalCredential());
        assertNull(captured.managementBearer());
        assertFalse(captured.prohibitedCredentialSourcePresent());
        assertTrue(captured.malformedTypedCredentialPresentation());
        assertFalse(captured.toString().contains(MANAGEMENT_BEARER));
        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
    }

    @Test
    void rejectsBlankPrincipalCredentialAsMalformedInsteadOfTreatingItAsMissing() throws Exception {
        MockHttpServletRequest request = request("POST", EXCHANGE_PATH);
        request.addHeader("X-Navi-Principal-Credential", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        TypedManagementAuthenticationRequest captured = capturedRequest();
        assertNotNull(captured.principalCredential());
        assertNull(captured.managementBearer());
        assertFalse(captured.prohibitedCredentialSourcePresent());
        assertTrue(captured.malformedTypedCredentialPresentation());
        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
    }

    @Test
    void passesAnOpaqueBearerToTheCanonicalParserInsteadOfAcceptingJwtCompatibility() throws Exception {
        String jwtLikeBearer = "eyJhbGciOiJSUzI1NiJ9.not-a-management-token.signature";
        MockHttpServletRequest request = request("POST", EXCHANGE_PATH);
        request.addHeader("Authorization", "Bearer " + jwtLikeBearer);
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        TypedManagementAuthenticationRequest captured = capturedRequest();
        assertNull(captured.principalCredential());
        assertNotNull(captured.managementBearer());
        assertFalse(captured.prohibitedCredentialSourcePresent());
        assertFalse(captured.toString().contains(jwtLikeBearer));
        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
    }

    @Test
    void marksDuplicateTypedCredentialHeadersAsAConflict() throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        request.addHeader("X-Navi-Principal-Credential", "navi-pc1.second-reference.second-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        TypedManagementAuthenticationRequest captured = capturedRequest();
        assertNull(captured.principalCredential());
        assertTrue(captured.prohibitedCredentialSourcePresent());
        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT);
    }

    @Test
    void marksDuplicateAuthorizationHeadersAsAConflict() throws Exception {
        MockHttpServletRequest request = request("POST", EXCHANGE_PATH);
        request.addHeader("Authorization", "Bearer " + MANAGEMENT_BEARER);
        request.addHeader("Authorization", "Bearer navi-mt1.second-reference.second-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        TypedManagementAuthenticationRequest captured = capturedRequest();
        assertNull(captured.principalCredential());
        assertNull(captured.managementBearer());
        assertTrue(captured.prohibitedCredentialSourcePresent());
        assertFalse(captured.malformedTypedCredentialPresentation());
        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT);
    }

    @ParameterizedTest(name = "query parameter {0} is never accepted by typed management")
    @MethodSource("queryParameterNames")
    void marksEveryQueryParameterIncludingEmptyCredentialAliasesAsAConflict(String parameterName) throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        request.addParameter(parameterName, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        TypedManagementAuthenticationRequest captured = capturedRequest();
        assertTrue(captured.prohibitedCredentialSourcePresent());
        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT);
    }

    @ParameterizedTest(name = "legacy/runtime header {0} is never accepted by typed management")
    @MethodSource("prohibitedCredentialHeaders")
    void marksEveryLegacyOrRuntimeCredentialHeaderIncludingBlankValuesAsAConflict(String headerName) throws Exception {
        MockHttpServletRequest request = exchangeRequest();
        request.addHeader(headerName, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        stubAllowedRouteAndAuthorizer();
        when(authorizer.authorize(any())).thenReturn(denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        TypedManagementAuthenticationRequest captured = capturedRequest();
        assertTrue(captured.prohibitedCredentialSourcePresent());
        assertDenied(response, 401, AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT);
    }

    private void stubAllowedRouteAndAuthorizer() {
        stubRoute(route("auth.exchange", "TYPED_MANAGEMENT_AUTH", "CANONICAL_ENFORCE"));
        when(authorizerProvider.getIfUnique()).thenReturn(authorizer);
    }

    private void stubRoute(AuthorizationRouteManifestEntry route) {
        when(routeCatalog.findByDeploymentMethodAndPath(
                AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER, "POST", EXCHANGE_PATH))
                .thenReturn(Optional.of(route));
    }

    private TypedManagementAuthenticationRequest capturedRequest() {
        ArgumentCaptor<TypedManagementAuthenticationRequest> requestCaptor =
                ArgumentCaptor.forClass(TypedManagementAuthenticationRequest.class);
        verify(authorizer).authorize(requestCaptor.capture());
        return requestCaptor.getValue();
    }

    private static MockHttpServletRequest exchangeRequest() {
        MockHttpServletRequest request = request("POST", EXCHANGE_PATH);
        request.setAttribute(TypedManagementAuthInterceptor.class.getName() + ".correlationId", "test-correlation-id");
        request.addHeader("X-Navi-Principal-Credential", PRINCIPAL_CREDENTIAL);
        return request;
    }

    private static MockHttpServletRequest bearerRequest() {
        MockHttpServletRequest request = request("POST", EXCHANGE_PATH);
        request.setAttribute(TypedManagementAuthInterceptor.class.getName() + ".correlationId", "test-correlation-id");
        request.addHeader("Authorization", "Bearer " + MANAGEMENT_BEARER);
        return request;
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static AuthorizationRouteManifestEntry route(String action, String surface, String migrationMode) {
        return new AuthorizationRouteManifestEntry(
                EXCHANGE_ROUTE,
                AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER,
                "POST",
                EXCHANGE_PATH,
                surface,
                "TypedManagementAuthController#exchange",
                "user-auth-module",
                "typed-management",
                "server-derived",
                action,
                "INSTANCE_ROOT_CONTROL|SAAS_PROVISIONING",
                "typed-management",
                "HIGH",
                migrationMode,
                "ENFORCE",
                "APPROVED",
                "test-only",
                Set.of(AuthorizationRequiredSection.PRINCIPAL,
                        AuthorizationRequiredSection.CREDENTIAL,
                        AuthorizationRequiredSection.TRUST,
                        AuthorizationRequiredSection.AUTHORITY));
    }

    private static TypedManagementAuthorizationResult allowed(ManagementAuthenticationContext context) {
        return new TypedManagementAuthorizationResult(true, null,
                context.credentialSource(), context, enforcementDecision(context));
    }

    private static TypedManagementAuthorizationResult allowed(ManagementAuthenticationContext context,
                                                              PolicyDecisionV1 decision) {
        return new TypedManagementAuthorizationResult(true, null,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL, context, decision);
    }

    private static TypedManagementAuthorizationResult denied(AuthorizationReasonCode reason) {
        return new TypedManagementAuthorizationResult(false, reason,
                TypedManagementCredentialSource.NONE, null, null);
    }

    private static ManagementAuthenticationContext context() {
        return contextWithCorrelation("test-correlation-id");
    }

    private static ManagementAuthenticationContext contextWithCorrelation(String correlationId) {
        return contextWithCredentialSource(TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL, correlationId);
    }

    private static ManagementAuthenticationContext contextWithCredentialSource(
            TypedManagementCredentialSource credentialSource,
            String correlationId
    ) {
        return new ManagementAuthenticationContext(
                "principal-record-id",
                AuthorizationPrincipalType.INSTANCE_ROOT,
                "principal-id",
                "upstream-system-id",
                "navigator-instance-id",
                "local",
                "S1_INSTANCE_ROOT",
                "credential-id",
                AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
                "credential-fingerprint",
                1,
                ManagementActionSetRegistry.INSTANCE_ROOT_CONTROL_V1,
                "ACTIVE",
                Instant.parse("2030-01-01T00:00:00Z"),
                credentialSource,
                null,
                EXCHANGE_ROUTE,
                "auth.exchange",
                correlationId);
    }

    private static PolicyDecisionV1 enforcementDecision(ManagementAuthenticationContext context) {
        return decision(
                AuthorizationEvaluationMode.ENFORCEMENT,
                AuthorizationDecisionOutcome.ALLOW,
                null,
                false,
                context.routeId(),
                context.actionId(),
                context.correlationId(),
                AuthorizationSchemaV1.SCHEMA_VERSION);
    }

    private static PolicyDecisionV1 decision(
            AuthorizationEvaluationMode evaluationMode,
            AuthorizationDecisionOutcome outcome,
            AuthorizationReasonCode reasonCode,
            boolean nonBinding,
            String routeId,
            String actionId,
            String correlationId,
            String schemaVersion
    ) {
        return new PolicyDecisionV1(
                schemaVersion,
                AuthorizationSchemaV1.POLICY_VERSION,
                AuthorizationSchemaV1.ACTION_CATALOG_VERSION,
                "test-build",
                "test-decision-id",
                correlationId,
                evaluationMode,
                outcome,
                reasonCode,
                nonBinding,
                actionId,
                routeId,
                Instant.parse("2030-01-01T00:00:00Z"));
    }

    private static void assertDenied(MockHttpServletResponse response, int status, AuthorizationReasonCode reason)
            throws Exception {
        assertEquals(status, response.getStatus());
        assertEquals("{\"reasonCode\":\"" + reason.name() + "\"}", response.getContentAsString());
    }

    private static Stream<String> queryParameterNames() {
        return Stream.of("token", "access_token", "authorization", "credential", "principalCredential", "ordinary");
    }

    private static Stream<String> prohibitedCredentialHeaders() {
        return Stream.of(
                "X-Navi-Admin-Key",
                "X-Navi-Admin-Api-Key",
                "X-Navi-Operator-Key",
                "X-Navi-Operator-Api-Key",
                "X-Client-App-Control-Key",
                "X-Client-App-Key",
                "X-Client-App-Secret",
                "X-Client-App-Access-Token",
                "X-App-Key",
                "X-App-Secret",
                "X-App-Access-Token",
                "X-Foggy-App-Key",
                "X-Foggy-App-Secret",
                "X-Foggy-App-Access-Token",
                "X-Task-Scoped-Token",
                "X-Navigator-Worker-Id",
                "X-Navigator-Worker-Credential",
                "X-Navigator-Worker-Lease-Id",
                "X-Worker-Id",
                "X-API-Key");
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> invalidAllowedDecisions() {
        ManagementAuthenticationContext context = context();
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("missing", null),
                org.junit.jupiter.params.provider.Arguments.of("preflight", decision(
                        AuthorizationEvaluationMode.PREFLIGHT, AuthorizationDecisionOutcome.ALLOW, null,
                        true, context.routeId(), context.actionId(), context.correlationId(),
                        AuthorizationSchemaV1.SCHEMA_VERSION)),
                org.junit.jupiter.params.provider.Arguments.of("non-binding enforcement", decision(
                        AuthorizationEvaluationMode.ENFORCEMENT, AuthorizationDecisionOutcome.ALLOW, null,
                        true, context.routeId(), context.actionId(), context.correlationId(),
                        AuthorizationSchemaV1.SCHEMA_VERSION)),
                org.junit.jupiter.params.provider.Arguments.of("deny", decision(
                        AuthorizationEvaluationMode.ENFORCEMENT, AuthorizationDecisionOutcome.DENY,
                        AuthorizationReasonCode.AUTHZ_ACTION_DENIED, false, context.routeId(), context.actionId(),
                        context.correlationId(), AuthorizationSchemaV1.SCHEMA_VERSION)),
                org.junit.jupiter.params.provider.Arguments.of("route mismatch", decision(
                        AuthorizationEvaluationMode.ENFORCEMENT, AuthorizationDecisionOutcome.ALLOW, null,
                        false, "mvc:post:/api/v1/management/v1/auth/not-exchange", context.actionId(),
                        context.correlationId(), AuthorizationSchemaV1.SCHEMA_VERSION)),
                org.junit.jupiter.params.provider.Arguments.of("action mismatch", decision(
                        AuthorizationEvaluationMode.ENFORCEMENT, AuthorizationDecisionOutcome.ALLOW, null,
                        false, context.routeId(), "auth.permissions.inspect", context.correlationId(),
                        AuthorizationSchemaV1.SCHEMA_VERSION)),
                org.junit.jupiter.params.provider.Arguments.of("correlation mismatch", decision(
                        AuthorizationEvaluationMode.ENFORCEMENT, AuthorizationDecisionOutcome.ALLOW, null,
                        false, context.routeId(), context.actionId(), "different-correlation-id",
                        AuthorizationSchemaV1.SCHEMA_VERSION)),
                org.junit.jupiter.params.provider.Arguments.of("schema mismatch", decision(
                        AuthorizationEvaluationMode.ENFORCEMENT, AuthorizationDecisionOutcome.ALLOW, null,
                        false, context.routeId(), context.actionId(), context.correlationId(), "unknown-schema")));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> unregisteredManagementNamespaceIngresses() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("GET", EXCHANGE_PATH),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/api/v1/management/v1/auth/whoami"),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/api/v1/management/v1/auth/permissions"),
                org.junit.jupiter.params.provider.Arguments.of("GET", "/api/v1/management/v1/auth/explain"),
                org.junit.jupiter.params.provider.Arguments.of("DELETE", EXCHANGE_PATH),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/api/v1/management/v1/auth/exchange/extra"),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/api/v1/management/v1/auth/issue"),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/api/v1/management/v1/workers"));
    }
}
