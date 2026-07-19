package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ManagementAuthorizationExplainResponseDTO;
import com.foggy.navigator.business.agent.model.dto.ManagementIssuedTokenResponseDTO;
import com.foggy.navigator.business.agent.model.dto.ManagementPermissionsResponseDTO;
import com.foggy.navigator.business.agent.model.dto.ManagementWhoamiResponseDTO;
import com.foggy.navigator.business.agent.model.form.ManagementAuthorizationExplainForm;
import com.foggy.navigator.business.agent.model.form.ManagementSecurityActionAuthorizeForm;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationDecisionOutcome;
import com.foggy.navigator.common.authorization.AuthorizationEvaluationMode;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.authorization.AuthorizationReasonCode;
import com.foggy.navigator.common.authorization.IssuedManagementToken;
import com.foggy.navigator.common.authorization.ManagementActionSetRegistry;
import com.foggy.navigator.common.authorization.ManagementAuthenticationContext;
import com.foggy.navigator.common.authorization.ManagementAuthenticationInspection;
import com.foggy.navigator.common.authorization.ManagementAuthorizationExplanation;
import com.foggy.navigator.common.authorization.ManagementSecurityActionAuthorizationRequest;
import com.foggy.navigator.common.authorization.ManagementTokenIssuanceResult;
import com.foggy.navigator.common.authorization.ManagementTokenPurpose;
import com.foggy.navigator.common.authorization.OpaqueSecretHasher;
import com.foggy.navigator.common.authorization.PolicyDecisionV1;
import com.foggy.navigator.common.authorization.TypedManagementAuthenticationInspector;
import com.foggy.navigator.common.authorization.TypedManagementAuthorizationExplainer;
import com.foggy.navigator.common.authorization.TypedManagementCredentialSource;
import com.foggy.navigator.common.authorization.TypedManagementTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagementAuthEndpointServiceTest {

    @Mock
    private ObjectProvider<TypedManagementTokenIssuer> tokenIssuerProvider;

    @Mock
    private ObjectProvider<TypedManagementAuthenticationInspector> inspectorProvider;

    @Mock
    private ObjectProvider<TypedManagementAuthorizationExplainer> explainerProvider;

    @Mock
    private TypedManagementTokenIssuer tokenIssuer;

    @Mock
    private TypedManagementAuthenticationInspector inspector;

    @Mock
    private TypedManagementAuthorizationExplainer explainer;

    private ManagementActionSetRegistry actionSetRegistry;
    private OpaqueSecretHasher hasher;
    private ManagementAuthEndpointService service;

    @BeforeEach
    void setUp() {
        actionSetRegistry = new ManagementActionSetRegistry();
        hasher = new OpaqueSecretHasher();
        service = new ManagementAuthEndpointService(
                tokenIssuerProvider, inspectorProvider, explainerProvider, hasher, actionSetRegistry);
    }

    @Test
    void exchangeIssuesOnlyControlAccessForExactControlEndpoint() {
        ManagementAuthenticationContext context = context(
                ManagementAuthEndpointService.EXCHANGE_ROUTE_ID,
                ManagementAuthEndpointService.EXCHANGE_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null);
        when(tokenIssuerProvider.getIfUnique()).thenReturn(tokenIssuer);
        when(tokenIssuer.exchangeControl(context)).thenReturn(issued(
                context, ManagementTokenPurpose.CONTROL_ACCESS,
                ManagementAuthEndpointService.EXCHANGE_ROUTE_ID,
                ManagementAuthEndpointService.EXCHANGE_ACTION));

        ManagementIssuedTokenResponseDTO response = service.exchange(context);

        assertEquals(ManagementTokenPurpose.CONTROL_ACCESS, response.getPurpose());
        assertTrue(response.getExpiresAt().isAfter(Instant.now()));
        assertFalse(response.toString().contains("issued-bearer"));
        verify(tokenIssuer).exchangeControl(context);
        verify(tokenIssuer, never()).authorizeSecurityAction(any(), any());
    }

    @Test
    void issuedTokenResponseSerializesOnlyTheFreshOpaqueBearerAndSafeMetadata() throws Exception {
        String newlyIssuedBearer = "fixture-newly-issued-opaque-bearer";
        String tokenId = "fixture-token-id";
        String tokenReference = "fixture-token-reference";
        ManagementIssuedTokenResponseDTO response = ManagementIssuedTokenResponseDTO.from(new IssuedManagementToken(
                newlyIssuedBearer, tokenId, tokenReference, ManagementTokenPurpose.CONTROL_ACCESS,
                Instant.parse("2030-01-01T00:05:00Z")));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertTrue(json.contains("\"token\":\"" + newlyIssuedBearer + "\""));
        assertEquals(1, json.split("\\\"token\\\"", -1).length - 1);
        assertTrue(json.contains("\"purpose\":\"CONTROL_ACCESS\""));
        assertTrue(json.contains("\"expiresAt\""));
        assertFalse(json.contains(tokenId));
        assertFalse(json.contains(tokenReference));
        assertFalse(response.toString().contains(newlyIssuedBearer));
        assertFalse(response.toString().contains(tokenId));
        assertFalse(response.toString().contains(tokenReference));
    }

    @Test
    void exchangeRejectsWrongRouteBeforeIssuerLookupOrSideEffect() {
        ManagementAuthenticationContext context = context(
                ManagementAuthEndpointService.WHOAMI_ROUTE_ID,
                ManagementAuthEndpointService.WHOAMI_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null);

        assertThrows(SecurityException.class, () -> service.exchange(context));

        verifyNoInteractions(tokenIssuerProvider, tokenIssuer);
    }

    @Test
    void exchangeFailsClosedWhenIssuerIsMissingOrReturnsWrongPurpose() {
        ManagementAuthenticationContext context = context(
                ManagementAuthEndpointService.EXCHANGE_ROUTE_ID,
                ManagementAuthEndpointService.EXCHANGE_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null);
        when(tokenIssuerProvider.getIfUnique()).thenReturn(null);

        assertThrows(SecurityException.class, () -> service.exchange(context));
        verifyNoInteractions(tokenIssuer);

        when(tokenIssuerProvider.getIfUnique()).thenReturn(tokenIssuer);
        when(tokenIssuer.exchangeControl(context)).thenReturn(issued(
                context, ManagementTokenPurpose.SECURITY_ACTION,
                ManagementAuthEndpointService.EXCHANGE_ROUTE_ID,
                ManagementAuthEndpointService.EXCHANGE_ACTION));

        assertThrows(SecurityException.class, () -> service.exchange(context));
        verify(tokenIssuer).exchangeControl(context);
    }

    @Test
    void exchangeSanitizesIssuerFailuresAndRejectsAnyResultContextMismatch() {
        ManagementAuthenticationContext context = context(
                ManagementAuthEndpointService.EXCHANGE_ROUTE_ID,
                ManagementAuthEndpointService.EXCHANGE_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null);
        when(tokenIssuerProvider.getIfUnique()).thenReturn(tokenIssuer);
        when(tokenIssuer.exchangeControl(context)).thenThrow(new IllegalStateException("issued-bearer-for-test"));

        SecurityException sanitized = assertThrows(SecurityException.class, () -> service.exchange(context));
        assertFalse(sanitized.getMessage().contains("issued-bearer-for-test"));
        assertFalse(sanitized.toString().contains("issued-bearer-for-test"));

        ManagementAuthenticationContext mismatchedUpstream = context(
                ManagementAuthEndpointService.EXCHANGE_ROUTE_ID,
                ManagementAuthEndpointService.EXCHANGE_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null,
                "different-upstream-system-id");
        doReturn(issued(
                mismatchedUpstream, ManagementTokenPurpose.CONTROL_ACCESS,
                ManagementAuthEndpointService.EXCHANGE_ROUTE_ID,
                ManagementAuthEndpointService.EXCHANGE_ACTION)).when(tokenIssuer).exchangeControl(context);

        assertThrows(SecurityException.class, () -> service.exchange(context));
    }

    @Test
    void securityAuthorizationCanonicalizesReferencesAndForwardsOnlyOpaqueProofMaterial() {
        ManagementAuthenticationContext context = context(
                ManagementAuthEndpointService.SECURITY_AUTHORIZE_ROUTE_ID,
                ManagementAuthEndpointService.SECURITY_AUTHORIZE_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null);
        ManagementSecurityActionAuthorizeForm form = securityForm();
        when(tokenIssuerProvider.getIfUnique()).thenReturn(tokenIssuer);
        when(tokenIssuer.authorizeSecurityAction(any(), any())).thenReturn(issued(
                context, ManagementTokenPurpose.SECURITY_ACTION,
                ManagementAuthEndpointService.SECURITY_AUTHORIZE_ROUTE_ID,
                ManagementAuthEndpointService.SECURITY_AUTHORIZE_ACTION));

        ManagementIssuedTokenResponseDTO response = service.authorizeSecurityAction(context, form);

        ArgumentCaptor<ManagementSecurityActionAuthorizationRequest> requestCaptor =
                ArgumentCaptor.forClass(ManagementSecurityActionAuthorizationRequest.class);
        verify(tokenIssuer).authorizeSecurityAction(org.mockito.ArgumentMatchers.eq(context), requestCaptor.capture());
        ManagementSecurityActionAuthorizationRequest captured = requestCaptor.getValue();
        assertEquals("credential.rotate", captured.actionBinding().actionId());
        assertEquals(hasher.hashUtf8("navi.management.reference.v1:target:target-01"),
                captured.actionBinding().targetDigest());
        assertEquals(hasher.hashUtf8("navi.management.reference.v1:impact:impact-01"),
                captured.actionBinding().impactDigest());
        assertEquals(hasher.hashUtf8("navi.management.reference.v1:reason:reason-01"),
                captured.actionBinding().reasonDigest());
        assertNotEquals(hasher.hashUtf8("target-01"), captured.actionBinding().targetDigest());
        assertEquals("[redacted]", captured.stepUpProof().toString());
        assertEquals("[redacted]", captured.approvalProof().toString());
        assertEquals(ManagementTokenPurpose.SECURITY_ACTION, response.getPurpose());
    }

    @Test
    void securityAuthorizationRejectsClientDigestsAndAssertionsBeforeIssuance() throws Exception {
        ManagementSecurityActionAuthorizeForm form = securityForm();

        assertThrows(IllegalArgumentException.class, () -> form.rejectClientTargetDigest("client-derived"));
        assertThrows(IllegalArgumentException.class, () -> form.rejectClientImpactDigest("client-derived"));
        assertThrows(IllegalArgumentException.class, () -> form.rejectClientReasonDigest("client-derived"));
        assertThrows(IllegalArgumentException.class, () -> form.rejectClientStepUpSatisfied(true));
        assertThrows(IllegalArgumentException.class, () -> form.rejectClientApprovalSatisfied(true));
        assertFalse(form.toString().contains("proof-value"));
        assertFalse(form.toString().contains("target-01"));
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(form);
        assertFalse(serialized.contains("proof-value"));
        assertFalse(serialized.contains("target-01"));
        assertFalse(serialized.contains("impact-01"));
        assertFalse(serialized.contains("reason-01"));
        verifyNoInteractions(tokenIssuerProvider, tokenIssuer);
    }

    @Test
    void securityFormDeserializerRejectsClientAssertionsAndDoesNotSerializeProofOrReferences() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String valid = "{\"actionId\":\"credential.rotate\",\"targetReference\":\"target-01\","
                + "\"impactReference\":\"impact-01\",\"reasonReference\":\"reason-01\","
                + "\"stepUpProof\":\"proof-value-step-up\",\"approvalProof\":\"proof-value-approval\"}";
        ManagementSecurityActionAuthorizeForm form = mapper.readValue(valid,
                ManagementSecurityActionAuthorizeForm.class);

        assertFalse(form.toString().contains("proof-value"));
        assertFalse(form.toString().contains("target-01"));
        assertThrows(Exception.class, () -> mapper.readValue(valid.substring(0, valid.length() - 1)
                        + ",\"targetDigest\":\"client-derived\"}",
                ManagementSecurityActionAuthorizeForm.class));
        assertThrows(Exception.class, () -> mapper.readValue(valid.substring(0, valid.length() - 1)
                        + ",\"stepUpSatisfied\":true}",
                ManagementSecurityActionAuthorizeForm.class));
        assertThrows(Exception.class, () -> mapper.readValue(valid.substring(0, valid.length() - 1)
                        + ",\"unexpected\":true}",
                ManagementSecurityActionAuthorizeForm.class));
    }

    @Test
    void inspectionPreservesAuthorityCeilingAndCurrentCredentialActionsAsSeparateSets() {
        ManagementAuthenticationContext whoami = context(
                ManagementAuthEndpointService.WHOAMI_ROUTE_ID,
                ManagementAuthEndpointService.WHOAMI_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null);
        ManagementAuthenticationContext permissions = context(
                ManagementAuthEndpointService.PERMISSIONS_ROUTE_ID,
                ManagementAuthEndpointService.PERMISSIONS_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null);
        when(inspectorProvider.getIfUnique()).thenReturn(inspector);
        when(inspector.inspect(whoami)).thenReturn(inspection(whoami));
        when(inspector.inspect(permissions)).thenReturn(inspection(permissions));

        ManagementWhoamiResponseDTO whoamiResponse = service.whoami(whoami);
        ManagementPermissionsResponseDTO permissionsResponse = service.permissions(permissions);

        assertTrue(whoamiResponse.getAuthorityCeilingActions().contains("credential.rotate"));
        assertFalse(whoamiResponse.getEffectiveCredentialActions().contains("credential.rotate"));
        assertEquals(whoamiResponse.getAuthorityCeilingActions(), permissionsResponse.getAuthorityCeilingActions());
        assertEquals(whoamiResponse.getEffectiveCredentialActions(), permissionsResponse.getEffectiveCredentialActions());
        assertNotEquals(whoamiResponse.getAuthorityCeilingActions(), whoamiResponse.getEffectiveCredentialActions());
    }

    @Test
    void inspectionRejectsSecurityActionBearerRatherThanTreatingItAsSessionCredential() {
        ManagementAuthenticationContext context = context(
                ManagementAuthEndpointService.WHOAMI_ROUTE_ID,
                ManagementAuthEndpointService.WHOAMI_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY,
                TypedManagementCredentialSource.MANAGEMENT_BEARER,
                ManagementTokenPurpose.SECURITY_ACTION);

        assertThrows(SecurityException.class, () -> service.whoami(context));

        verifyNoInteractions(inspectorProvider, inspector);
    }

    @Test
    void eachEndpointRejectsAContextForAnotherRouteBeforeItsFacadeIsUsed() {
        ManagementAuthenticationContext exchange = context(
                ManagementAuthEndpointService.EXCHANGE_ROUTE_ID,
                ManagementAuthEndpointService.EXCHANGE_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null);
        ManagementAuthenticationContext security = context(
                ManagementAuthEndpointService.SECURITY_AUTHORIZE_ROUTE_ID,
                ManagementAuthEndpointService.SECURITY_AUTHORIZE_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null);

        assertThrows(SecurityException.class, () -> service.exchange(security));
        assertThrows(SecurityException.class, () -> service.authorizeSecurityAction(exchange, securityForm()));
        assertThrows(SecurityException.class, () -> service.whoami(exchange));
        assertThrows(SecurityException.class, () -> service.permissions(exchange));
        assertThrows(SecurityException.class, () -> service.explain(exchange, explainForm()));

        verifyNoInteractions(tokenIssuerProvider, tokenIssuer, inspectorProvider, inspector, explainerProvider, explainer);
    }

    @Test
    void explainOnlyReturnsValidatedNonBindingPreflightAndDoesNotExposeReferencesOrDigests() throws Exception {
        ManagementAuthenticationContext context = context(
                ManagementAuthEndpointService.EXPLAIN_ROUTE_ID,
                ManagementAuthEndpointService.EXPLAIN_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null);
        ManagementAuthorizationExplainForm form = explainForm();
        when(explainerProvider.getIfUnique()).thenReturn(explainer);
        when(explainer.explain(any(), any())).thenReturn(new ManagementAuthorizationExplanation(
                true, null, true, decision(
                        ManagementAuthEndpointService.SECURITY_AUTHORIZE_ROUTE_ID,
                        ManagementAuthEndpointService.SECURITY_AUTHORIZE_ACTION,
                        AuthorizationEvaluationMode.PREFLIGHT,
                        AuthorizationDecisionOutcome.ALLOW,
                        null,
                        true)));

        ManagementAuthorizationExplainResponseDTO response = service.explain(context, form);

        assertTrue(response.isAllowed());
        assertTrue(response.isNonBinding());
        assertEquals(ManagementAuthEndpointService.SECURITY_AUTHORIZE_ACTION, response.getActionId());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("target-01"));
        assertFalse(json.contains("impact-01"));
        assertFalse(json.contains("reason-01"));
        assertFalse(json.contains("Digest"));
        assertFalse(json.contains("proof"));
    }

    @Test
    void explainRejectsBindingResultOrRouteActionMismatch() {
        ManagementAuthenticationContext context = context(
                ManagementAuthEndpointService.EXPLAIN_ROUTE_ID,
                ManagementAuthEndpointService.EXPLAIN_ACTION,
                AuthorizationPrincipalType.INSTANCE_ROOT,
                AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                null);
        when(explainerProvider.getIfUnique()).thenReturn(explainer);
        when(explainer.explain(any(), any())).thenReturn(new ManagementAuthorizationExplanation(
                true, null, false, decision(
                        ManagementAuthEndpointService.SECURITY_AUTHORIZE_ROUTE_ID,
                        ManagementAuthEndpointService.SECURITY_AUTHORIZE_ACTION,
                        AuthorizationEvaluationMode.ENFORCEMENT,
                        AuthorizationDecisionOutcome.ALLOW,
                        null,
                        false)));

        assertThrows(SecurityException.class, () -> service.explain(context, explainForm()));
    }

    private ManagementSecurityActionAuthorizeForm securityForm() {
        ManagementSecurityActionAuthorizeForm form = new ManagementSecurityActionAuthorizeForm();
        form.setActionId("credential.rotate");
        form.setTargetReference("target-01");
        form.setImpactReference("impact-01");
        form.setReasonReference("reason-01");
        form.setStepUpProof("proof-value-step-up");
        form.setApprovalProof("proof-value-approval");
        return form;
    }

    private ManagementAuthorizationExplainForm explainForm() {
        ManagementAuthorizationExplainForm form = new ManagementAuthorizationExplainForm();
        form.setRouteId(ManagementAuthEndpointService.SECURITY_AUTHORIZE_ROUTE_ID);
        form.setActionId(ManagementAuthEndpointService.SECURITY_AUTHORIZE_ACTION);
        form.setTargetReference("target-01");
        form.setImpactReference("impact-01");
        form.setReasonReference("reason-01");
        return form;
    }

    private ManagementAuthenticationInspection inspection(ManagementAuthenticationContext context) {
        return new ManagementAuthenticationInspection(
                context,
                actionSetRegistry.authorityCeilingActions(context.principalType()),
                actionSetRegistry.effectiveActions(context.actionSetRef()));
    }

    private ManagementTokenIssuanceResult issued(
            ManagementAuthenticationContext context,
            ManagementTokenPurpose purpose,
            String routeId,
            String actionId
    ) {
        IssuedManagementToken token = new IssuedManagementToken(
                "issued-bearer-for-test", "issued-token-id", "issued-token-reference", purpose,
                Instant.now().plusSeconds(300));
        return new ManagementTokenIssuanceResult(true, null, context, token, decision(
                routeId, actionId, AuthorizationEvaluationMode.ENFORCEMENT,
                AuthorizationDecisionOutcome.ALLOW, null, false));
    }

    private PolicyDecisionV1 decision(
            String routeId,
            String actionId,
            AuthorizationEvaluationMode mode,
            AuthorizationDecisionOutcome outcome,
            AuthorizationReasonCode reasonCode,
            boolean nonBinding
    ) {
        return new PolicyDecisionV1(
                "navi.authorization.v1", "policy-v1", "catalog-v1", "test-build",
                "decision-id", "correlation-id", mode, outcome, reasonCode, nonBinding,
                actionId, routeId, Instant.now());
    }

    private ManagementAuthenticationContext context(
            String routeId,
            String actionId,
            AuthorizationPrincipalType principalType,
            AuthorizationCredentialLane lane,
            TypedManagementCredentialSource source,
            ManagementTokenPurpose tokenPurpose
    ) {
        return context(routeId, actionId, principalType, lane, source, tokenPurpose, "source-upstream-system-id");
    }

    private ManagementAuthenticationContext context(
            String routeId,
            String actionId,
            AuthorizationPrincipalType principalType,
            AuthorizationCredentialLane lane,
            TypedManagementCredentialSource source,
            ManagementTokenPurpose tokenPurpose,
            String sourceUpstreamSystemId
    ) {
        return new ManagementAuthenticationContext(
                "principal-record-id",
                principalType,
                "principal-id",
                sourceUpstreamSystemId,
                "navigator-instance-id",
                "internal-dev",
                "S1_INSTANCE_ROOT",
                "credential-id",
                lane,
                "credential-fingerprint",
                1,
                actionSetFor(lane),
                "ACTIVE",
                Instant.parse("2030-01-01T00:00:00Z"),
                source,
                tokenPurpose,
                routeId,
                actionId,
                "correlation-id");
    }

    private static String actionSetFor(AuthorizationCredentialLane lane) {
        return switch (lane) {
            case INSTANCE_ROOT_CONTROL -> ManagementActionSetRegistry.INSTANCE_ROOT_CONTROL_V1;
            case INSTANCE_ROOT_SECURITY -> ManagementActionSetRegistry.INSTANCE_ROOT_SECURITY_V1;
            case SAAS_PROVISIONING -> ManagementActionSetRegistry.SAAS_PROVISIONING_V1;
            case SAAS_SECURITY_ADMIN -> ManagementActionSetRegistry.SAAS_SECURITY_ADMIN_V1;
            default -> throw new IllegalArgumentException("unsupported test lane");
        };
    }
}
