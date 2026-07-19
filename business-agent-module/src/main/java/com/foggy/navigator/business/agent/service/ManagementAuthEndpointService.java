package com.foggy.navigator.business.agent.service;

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
import com.foggy.navigator.common.authorization.ManagementAuthorizationExplainRequest;
import com.foggy.navigator.common.authorization.ManagementAuthorizationExplanation;
import com.foggy.navigator.common.authorization.ManagementSecurityActionAuthorizationRequest;
import com.foggy.navigator.common.authorization.ManagementSecurityActionBinding;
import com.foggy.navigator.common.authorization.ManagementTokenIssuanceResult;
import com.foggy.navigator.common.authorization.ManagementTokenPurpose;
import com.foggy.navigator.common.authorization.OpaqueSecretHasher;
import com.foggy.navigator.common.authorization.PolicyDecisionV1;
import com.foggy.navigator.common.authorization.TypedManagementAuthenticationInspector;
import com.foggy.navigator.common.authorization.TypedManagementAuthorizationExplainer;
import com.foggy.navigator.common.authorization.TypedManagementCredentialSource;
import com.foggy.navigator.common.authorization.TypedManagementTokenIssuer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Endpoint-local adapter for the typed-management auth namespace.
 *
 * <p>The MVC controller only obtains the safe context placed by the canonical
 * ingress guard. This service never reads HTTP material, entities, or
 * repositories; it validates the fixed endpoint contract and delegates to the
 * common canonical facades. A missing, ambiguous, malformed, or inconsistent
 * facade result is always a deny.</p>
 */
@Service
public class ManagementAuthEndpointService {

    public static final String BASE_PATH = "/api/v1/management/v1/auth";
    public static final String EXCHANGE_ROUTE_ID = "mvc:post:" + BASE_PATH + "/exchange";
    public static final String SECURITY_AUTHORIZE_ROUTE_ID =
            "mvc:post:" + BASE_PATH + "/security-actions/authorize";
    public static final String WHOAMI_ROUTE_ID = "mvc:get:" + BASE_PATH + "/whoami";
    public static final String PERMISSIONS_ROUTE_ID = "mvc:get:" + BASE_PATH + "/permissions";
    public static final String EXPLAIN_ROUTE_ID = "mvc:post:" + BASE_PATH + "/explain";

    public static final String EXCHANGE_ACTION = "auth.exchange";
    public static final String SECURITY_AUTHORIZE_ACTION = "auth.security-authorize";
    public static final String WHOAMI_ACTION = "auth.whoami";
    public static final String PERMISSIONS_ACTION = "auth.permissions.inspect";
    public static final String EXPLAIN_ACTION = "auth.decision.explain";

    private static final String REFERENCE_CANONICALIZATION_VERSION = "navi.management.reference.v1";
    private static final Set<AuthorizationCredentialLane> CONTROL_LANES = Set.of(
            AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
            AuthorizationCredentialLane.SAAS_PROVISIONING);
    private static final Set<AuthorizationCredentialLane> SECURITY_LANES = Set.of(
            AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY,
            AuthorizationCredentialLane.SAAS_SECURITY_ADMIN);
    private static final Set<AuthorizationCredentialLane> MANAGEMENT_LANES = Set.of(
            AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
            AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY,
            AuthorizationCredentialLane.SAAS_PROVISIONING,
            AuthorizationCredentialLane.SAAS_SECURITY_ADMIN);

    private final ObjectProvider<TypedManagementTokenIssuer> tokenIssuerProvider;
    private final ObjectProvider<TypedManagementAuthenticationInspector> inspectorProvider;
    private final ObjectProvider<TypedManagementAuthorizationExplainer> explainerProvider;
    private final OpaqueSecretHasher opaqueSecretHasher;
    private final ManagementActionSetRegistry actionSetRegistry;

    public ManagementAuthEndpointService(
            ObjectProvider<TypedManagementTokenIssuer> tokenIssuerProvider,
            ObjectProvider<TypedManagementAuthenticationInspector> inspectorProvider,
            ObjectProvider<TypedManagementAuthorizationExplainer> explainerProvider,
            OpaqueSecretHasher opaqueSecretHasher,
            ManagementActionSetRegistry actionSetRegistry
    ) {
        this.tokenIssuerProvider = tokenIssuerProvider;
        this.inspectorProvider = inspectorProvider;
        this.explainerProvider = explainerProvider;
        this.opaqueSecretHasher = opaqueSecretHasher;
        this.actionSetRegistry = actionSetRegistry;
    }

    public ManagementIssuedTokenResponseDTO exchange(ManagementAuthenticationContext context) {
        requireEndpointContext(context, EXCHANGE_ROUTE_ID, EXCHANGE_ACTION, CONTROL_LANES, false);
        TypedManagementTokenIssuer issuer = requireUnique(tokenIssuerProvider);
        ManagementTokenIssuanceResult result = invoke(() -> issuer.exchangeControl(context));
        return ManagementIssuedTokenResponseDTO.from(requireIssuedToken(
                result, context, EXCHANGE_ROUTE_ID, EXCHANGE_ACTION, CONTROL_LANES,
                ManagementTokenPurpose.CONTROL_ACCESS));
    }

    public ManagementIssuedTokenResponseDTO authorizeSecurityAction(
            ManagementAuthenticationContext context,
            ManagementSecurityActionAuthorizeForm form
    ) {
        requireEndpointContext(context, SECURITY_AUTHORIZE_ROUTE_ID, SECURITY_AUTHORIZE_ACTION, SECURITY_LANES, false);
        ManagementSecurityActionAuthorizeForm.CanonicalActionInput input = requireForm(form).toCanonicalActionInput();
        if (!actionSetRegistry.allows(context.actionSetRef(), input.actionId())
                || actionSetRegistry.managementEndpointActions().containsValue(input.actionId())) {
            throw denied(AuthorizationReasonCode.AUTHZ_ACTION_DENIED);
        }
        ManagementSecurityActionBinding binding = binding(
                input.actionId(), input.targetReference(), input.impactReference(), input.reasonReference());
        ManagementSecurityActionAuthorizationRequest authorizationRequest =
                new ManagementSecurityActionAuthorizationRequest(binding, input.stepUpProof(), input.approvalProof());

        TypedManagementTokenIssuer issuer = requireUnique(tokenIssuerProvider);
        ManagementTokenIssuanceResult result = invoke(() -> issuer.authorizeSecurityAction(context, authorizationRequest));
        return ManagementIssuedTokenResponseDTO.from(requireIssuedToken(
                result, context, SECURITY_AUTHORIZE_ROUTE_ID, SECURITY_AUTHORIZE_ACTION, SECURITY_LANES,
                ManagementTokenPurpose.SECURITY_ACTION));
    }

    public ManagementWhoamiResponseDTO whoami(ManagementAuthenticationContext context) {
        return ManagementWhoamiResponseDTO.from(inspect(context, WHOAMI_ROUTE_ID, WHOAMI_ACTION));
    }

    public ManagementPermissionsResponseDTO permissions(ManagementAuthenticationContext context) {
        return ManagementPermissionsResponseDTO.from(inspect(context, PERMISSIONS_ROUTE_ID, PERMISSIONS_ACTION));
    }

    public ManagementAuthorizationExplainResponseDTO explain(
            ManagementAuthenticationContext context,
            ManagementAuthorizationExplainForm form
    ) {
        requireEndpointContext(context, EXPLAIN_ROUTE_ID, EXPLAIN_ACTION, MANAGEMENT_LANES, true);
        ManagementAuthorizationExplainForm.CanonicalExplainInput input = requireForm(form).toCanonicalExplainInput();
        if (!actionSetRegistry.isRegisteredEndpointAction(input.routeId(), input.actionId())
                || !actionSetRegistry.allows(context.actionSetRef(), input.actionId())) {
            throw denied(AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
        }
        ManagementSecurityActionBinding binding = input.hasActionBinding()
                ? binding(input.actionId(), input.targetReference(), input.impactReference(), input.reasonReference())
                : null;
        ManagementAuthorizationExplainRequest request = new ManagementAuthorizationExplainRequest(
                input.routeId(), input.actionId(), binding);

        TypedManagementAuthorizationExplainer explainer = requireUnique(explainerProvider);
        ManagementAuthorizationExplanation explanation = invoke(() -> explainer.explain(context, request));
        requireValidPreflight(explanation, input.routeId(), input.actionId());
        return ManagementAuthorizationExplainResponseDTO.from(explanation);
    }

    private ManagementAuthenticationInspection inspect(ManagementAuthenticationContext context,
                                                        String routeId,
                                                        String actionId) {
        requireEndpointContext(context, routeId, actionId, MANAGEMENT_LANES, true);
        TypedManagementAuthenticationInspector inspector = requireUnique(inspectorProvider);
        ManagementAuthenticationInspection inspection = invoke(() -> inspector.inspect(context));
        if (inspection == null || inspection.authenticationContext() == null) {
            throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        ManagementAuthenticationContext inspectedContext = inspection.authenticationContext();
        requireEndpointContext(inspectedContext, routeId, actionId, MANAGEMENT_LANES, true);
        if (!sameContextBinding(context, inspectedContext)
                || !actionSetRegistry.authorityCeilingActions(inspectedContext.principalType())
                .equals(inspection.authorityCeilingActions())
                || !actionSetRegistry.effectiveActions(inspectedContext.actionSetRef())
                .equals(inspection.effectiveCredentialActions())) {
            throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        return inspection;
    }

    private IssuedManagementToken requireIssuedToken(
            ManagementTokenIssuanceResult result,
            ManagementAuthenticationContext requestContext,
            String routeId,
            String actionId,
            Set<AuthorizationCredentialLane> allowedLanes,
            ManagementTokenPurpose expectedPurpose
    ) {
        if (result == null || !result.issued()) {
            throw denied(result == null || result.reasonCode() == null
                    ? AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID : result.reasonCode());
        }
        if (result.reasonCode() != null || result.issuedToken() == null || result.decision() == null
                || result.authenticationContext() == null) {
            throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        ManagementAuthenticationContext resultContext = result.authenticationContext();
        requireEndpointContext(resultContext, routeId, actionId, allowedLanes, false);
        if (!sameContextBinding(requestContext, resultContext)) {
            throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        IssuedManagementToken token = result.issuedToken();
        if (token.purpose() != expectedPurpose || blank(token.bearerToken()) || blank(token.tokenId())
                || blank(token.tokenReference()) || token.expiresAt() == null || !token.expiresAt().isAfter(Instant.now())) {
            throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        requireEnforcementDecision(result.decision(), routeId, actionId);
        return token;
    }

    private void requireValidPreflight(ManagementAuthorizationExplanation explanation,
                                       String routeId,
                                       String actionId) {
        if (explanation == null || !explanation.nonBinding() || explanation.decision() == null) {
            throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        PolicyDecisionV1 decision = explanation.decision();
        if (decision.evaluationMode() != AuthorizationEvaluationMode.PREFLIGHT || !decision.nonBinding()
                || !same(routeId, decision.routeId()) || !same(actionId, decision.actionId())
                || blank(decision.decisionId()) || blank(decision.correlationId())
                || decision.evaluatedAt() == null
                || (explanation.allowed() && (decision.decision() != AuthorizationDecisionOutcome.ALLOW
                || explanation.reasonCode() != null || decision.reasonCode() != null))
                || (!explanation.allowed() && (decision.decision() != AuthorizationDecisionOutcome.DENY
                || explanation.reasonCode() == null || decision.reasonCode() != explanation.reasonCode()))) {
            throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
    }

    private void requireEnforcementDecision(PolicyDecisionV1 decision, String routeId, String actionId) {
        if (decision.evaluationMode() != AuthorizationEvaluationMode.ENFORCEMENT || decision.nonBinding()
                || decision.decision() != AuthorizationDecisionOutcome.ALLOW || decision.reasonCode() != null
                || !same(routeId, decision.routeId()) || !same(actionId, decision.actionId())
                || blank(decision.decisionId()) || blank(decision.correlationId()) || decision.evaluatedAt() == null) {
            throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
    }

    private void requireEndpointContext(
            ManagementAuthenticationContext context,
            String expectedRouteId,
            String expectedActionId,
            Set<AuthorizationCredentialLane> allowedLanes,
            boolean allowControlAccessBearer
    ) {
        if (context == null || actionSetRegistry == null || opaqueSecretHasher == null
                || !same(expectedRouteId, context.routeId()) || !same(expectedActionId, context.actionId())
                || !actionSetRegistry.isRegisteredEndpointAction(expectedRouteId, expectedActionId)
                || !isSupportedPrincipalLane(context.principalType(), context.credentialLane())
                || !allowedLanes.contains(context.credentialLane())
                || !actionSetRegistry.matches(context.principalType(), context.credentialLane(), context.actionSetRef())
                || blank(context.principalRecordId()) || blank(context.principalId())
                || blank(context.navigatorInstanceId()) || blank(context.environmentProfile())
                || blank(context.credentialId()) || blank(context.credentialFingerprint())
                || context.credentialGeneration() == null || context.credentialGeneration() < 1
                || blank(context.correlationId()) || context.credentialExpiresAt() == null) {
            throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }

        if (context.credentialSource() == TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL
                && context.tokenPurpose() == null) {
            return;
        }
        if (allowControlAccessBearer
                && context.credentialSource() == TypedManagementCredentialSource.MANAGEMENT_BEARER
                && context.tokenPurpose() == ManagementTokenPurpose.CONTROL_ACCESS
                && CONTROL_LANES.contains(context.credentialLane())) {
            return;
        }
        throw denied(context.credentialSource() == null || context.credentialSource() == TypedManagementCredentialSource.NONE
                ? AuthorizationReasonCode.AUTHN_CREDENTIAL_MISSING
                : AuthorizationReasonCode.AUTHZ_CREDENTIAL_LANE_DENIED);
    }

    private static boolean isSupportedPrincipalLane(AuthorizationPrincipalType principalType,
                                                    AuthorizationCredentialLane credentialLane) {
        return (principalType == AuthorizationPrincipalType.INSTANCE_ROOT
                && (credentialLane == AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL
                || credentialLane == AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY))
                || (principalType == AuthorizationPrincipalType.SAAS_PLATFORM
                && (credentialLane == AuthorizationCredentialLane.SAAS_PROVISIONING
                || credentialLane == AuthorizationCredentialLane.SAAS_SECURITY_ADMIN));
    }

    private ManagementSecurityActionBinding binding(
            String actionId,
            String targetReference,
            String impactReference,
            String reasonReference
    ) {
        return ManagementSecurityActionBinding.fromCanonicalRepresentations(
                opaqueSecretHasher,
                actionId,
                canonicalReference("target", targetReference),
                canonicalReference("impact", impactReference),
                canonicalReference("reason", reasonReference));
    }

    private static String canonicalReference(String kind, String reference) {
        if (blank(reference)) {
            throw new IllegalArgumentException("management action reference is required");
        }
        return REFERENCE_CANONICALIZATION_VERSION + ":" + kind + ":" + reference;
    }

    private static <T> T requireForm(T form) {
        if (form == null) {
            throw new IllegalArgumentException("management authorization input is required");
        }
        return form;
    }

    private static boolean sameContextBinding(ManagementAuthenticationContext left,
                                              ManagementAuthenticationContext right) {
        return left != null && right != null
                && left.principalType() == right.principalType()
                && left.credentialLane() == right.credentialLane()
                && left.credentialSource() == right.credentialSource()
                && left.tokenPurpose() == right.tokenPurpose()
                && Objects.equals(left.principalRecordId(), right.principalRecordId())
                && Objects.equals(left.principalId(), right.principalId())
                && Objects.equals(left.sourceUpstreamSystemId(), right.sourceUpstreamSystemId())
                && Objects.equals(left.navigatorInstanceId(), right.navigatorInstanceId())
                && Objects.equals(left.environmentProfile(), right.environmentProfile())
                && Objects.equals(left.upstreamTrustProfile(), right.upstreamTrustProfile())
                && Objects.equals(left.credentialId(), right.credentialId())
                && Objects.equals(left.credentialFingerprint(), right.credentialFingerprint())
                && Objects.equals(left.credentialGeneration(), right.credentialGeneration())
                && Objects.equals(left.actionSetRef(), right.actionSetRef())
                && Objects.equals(left.credentialStatus(), right.credentialStatus())
                && Objects.equals(left.credentialExpiresAt(), right.credentialExpiresAt())
                && Objects.equals(left.routeId(), right.routeId())
                && Objects.equals(left.actionId(), right.actionId())
                && Objects.equals(left.correlationId(), right.correlationId());
    }

    private static boolean same(String left, String right) {
        return Objects.equals(left, right);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static SecurityException denied(AuthorizationReasonCode reasonCode) {
        AuthorizationReasonCode stableReason = reasonCode == null
                ? AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID : reasonCode;
        String prefix = stableReason.name().startsWith("AUTHN_")
                ? "typed management credential is required"
                : "typed management authorization denied";
        return new SecurityException(prefix + " (" + stableReason.name() + ")");
    }

    private static <T> T invoke(Invoker<T> invoker) {
        try {
            T value = invoker.invoke();
            if (value == null) {
                throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
            }
            return value;
        } catch (SecurityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
    }

    private static <T> T requireUnique(ObjectProvider<T> provider) {
        try {
            T value = provider == null ? null : provider.getIfUnique();
            if (value == null) {
                throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
            }
            return value;
        } catch (SecurityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
    }

    @FunctionalInterface
    private interface Invoker<T> {

        T invoke();
    }
}
