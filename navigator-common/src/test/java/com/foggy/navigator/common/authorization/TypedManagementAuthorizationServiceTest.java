package com.foggy.navigator.common.authorization;

import com.foggy.navigator.common.entity.AuthorizationCredentialEntity;
import com.foggy.navigator.common.entity.AuthorizationManagementTokenEntity;
import com.foggy.navigator.common.entity.AuthorizationPrincipalEntity;
import com.foggy.navigator.common.repository.AuthorizationCredentialRepository;
import com.foggy.navigator.common.repository.AuthorizationManagementTokenRepository;
import com.foggy.navigator.common.repository.AuthorizationPrincipalRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TypedManagementAuthorizationServiceTest {

    private static final String EXCHANGE_ROUTE = "mvc:post:/api/v1/management/v1/auth/exchange";
    private static final String EXCHANGE_ACTION = "auth.exchange";
    private static final String SECURITY_ROUTE = "mvc:post:/api/v1/management/v1/auth/security-actions/authorize";
    private static final String SECURITY_ACTION = "auth.security-authorize";
    private static final String WHOAMI_ROUTE = "mvc:get:/api/v1/management/v1/auth/whoami";
    private static final String WHOAMI_ACTION = "auth.whoami";

    @Test
    void deniesPrincipalCredentialWhenNoVerifierIsInstalled() {
        Fixture fixture = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, false, List.of());

        TypedManagementAuthorizationResult result = fixture.service.authorize(fixture.direct(EXCHANGE_ROUTE, EXCHANGE_ACTION));

        assertFalse(result.allowed());
        assertEquals(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID, result.reasonCode());
        verify(fixture.credentials, never()).findByCredentialIdAndNavigatorInstanceIdAndEnvironmentProfile(
                anyString(), anyString(), anyString());
    }

    @Test
    void rejectsMissingConflictBlankAndMalformedCredentialSourcesWithoutFallback() {
        Fixture fixture = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, true, List.of());

        assertReason(fixture.service.authorize(new TypedManagementAuthenticationRequest(
                        EXCHANGE_ROUTE, EXCHANGE_ACTION, "server-correlation", null, null, false)),
                AuthorizationReasonCode.AUTHN_CREDENTIAL_MISSING);
        assertReason(fixture.service.authorize(new TypedManagementAuthenticationRequest(
                        EXCHANGE_ROUTE, EXCHANGE_ACTION, "server-correlation", fixture.principalPresentation,
                        OpaqueSecretMaterial.of("navi-mt1.ZmFrZQ.token"), false)),
                AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT);
        assertReason(fixture.service.authorize(new TypedManagementAuthenticationRequest(
                        EXCHANGE_ROUTE, EXCHANGE_ACTION, "server-correlation", fixture.principalPresentation,
                        null, true)), AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT);
        assertReason(fixture.service.authorize(TypedManagementAuthenticationRequest.fromHttpHeaders(
                        EXCHANGE_ROUTE, EXCHANGE_ACTION, "server-correlation", "", null, false)),
                AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        assertReason(fixture.service.authorize(TypedManagementAuthenticationRequest.fromHttpHeaders(
                        EXCHANGE_ROUTE, EXCHANGE_ACTION, "server-correlation", null, "Basic opaque", false)),
                AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        assertReason(fixture.service.authorize(TypedManagementAuthenticationRequest.fromHttpHeaders(
                        EXCHANGE_ROUTE, EXCHANGE_ACTION, "server-correlation", null, "Bearer fixture.jwt.parts", false)),
                AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
    }

    @Test
    void rejectsScopePrincipalLaneStatusExpiryAndGenerationMismatches() {
        Fixture instanceMismatch = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, true, List.of());
        instanceMismatch.credential.setNavigatorInstanceId("another-instance");
        when(instanceMismatch.credentials.findByVerifierReferenceAndNavigatorInstanceIdAndEnvironmentProfile(
                instanceMismatch.credential.getVerifierReference(), instanceMismatch.instanceId, instanceMismatch.profile))
                .thenReturn(Optional.empty());
        when(instanceMismatch.credentials.findByVerifierReference(instanceMismatch.credential.getVerifierReference()))
                .thenReturn(Optional.of(instanceMismatch.credential));
        assertReason(instanceMismatch.service.authorize(instanceMismatch.direct(EXCHANGE_ROUTE, EXCHANGE_ACTION)),
                AuthorizationReasonCode.AUTHZ_INSTANCE_MISMATCH);

        Fixture environmentMismatch = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, true, List.of());
        environmentMismatch.credential.setEnvironmentProfile("another-profile");
        when(environmentMismatch.credentials.findByVerifierReferenceAndNavigatorInstanceIdAndEnvironmentProfile(
                environmentMismatch.credential.getVerifierReference(), environmentMismatch.instanceId,
                environmentMismatch.profile)).thenReturn(Optional.empty());
        when(environmentMismatch.credentials.findByVerifierReference(environmentMismatch.credential.getVerifierReference()))
                .thenReturn(Optional.of(environmentMismatch.credential));
        assertReason(environmentMismatch.service.authorize(environmentMismatch.direct(EXCHANGE_ROUTE, EXCHANGE_ACTION)),
                AuthorizationReasonCode.AUTHZ_ENVIRONMENT_MISMATCH);

        Fixture unknownPrincipalType = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, true, List.of());
        unknownPrincipalType.credential.setPrincipalType("NOT_A_TYPED_PRINCIPAL");
        assertReason(unknownPrincipalType.service.authorize(unknownPrincipalType.direct(EXCHANGE_ROUTE, EXCHANGE_ACTION)),
                AuthorizationReasonCode.AUTHZ_PRINCIPAL_TYPE_DENIED);

        Fixture legacyLane = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, true, List.of());
        legacyLane.credential.setCredentialLane(AuthorizationCredentialLane.LEGACY_UPSTREAM_ADMIN.name());
        assertReason(legacyLane.service.authorize(legacyLane.direct(EXCHANGE_ROUTE, EXCHANGE_ACTION)),
                AuthorizationReasonCode.AUTHZ_CREDENTIAL_LANE_DENIED);

        Fixture revoked = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, true, List.of());
        revoked.credential.setStatus("REVOKED");
        assertReason(revoked.service.authorize(revoked.direct(EXCHANGE_ROUTE, EXCHANGE_ACTION)),
                AuthorizationReasonCode.AUTHN_CREDENTIAL_REVOKED);

        Fixture expired = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, true, List.of());
        expired.credential.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertReason(expired.service.authorize(expired.direct(EXCHANGE_ROUTE, EXCHANGE_ACTION)),
                AuthorizationReasonCode.AUTHN_CREDENTIAL_EXPIRED);

        Fixture staleGeneration = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, true, List.of());
        staleGeneration.credential.setGeneration(0);
        assertReason(staleGeneration.service.authorize(staleGeneration.direct(EXCHANGE_ROUTE, EXCHANGE_ACTION)),
                AuthorizationReasonCode.AUTHN_CREDENTIAL_GENERATION_MISMATCH);

        Fixture missingPrincipal = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, true, List.of());
        when(missingPrincipal.principals
                .findByPrincipalRecordIdAndNavigatorInstanceIdAndEnvironmentProfileAndPrincipalTypeAndPrincipalIdAndStatus(
                        missingPrincipal.credential.getPrincipalRecordId(), missingPrincipal.instanceId, missingPrincipal.profile,
                        missingPrincipal.credential.getPrincipalType(), missingPrincipal.credential.getPrincipalId(), "ACTIVE"))
                .thenReturn(Optional.empty());
        assertReason(missingPrincipal.service.authorize(missingPrincipal.direct(EXCHANGE_ROUTE, EXCHANGE_ACTION)),
                AuthorizationReasonCode.AUTHZ_PRINCIPAL_TYPE_DENIED);
    }

    @Test
    void issuesOnlyShortControlAccessFromTheDirectControlExchangeContext() {
        Fixture fixture = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, true, List.of());
        TypedManagementAuthorizationResult authentication = fixture.service.authorize(fixture.direct(EXCHANGE_ROUTE, EXCHANGE_ACTION));

        assertTrue(authentication.allowed());
        verify(fixture.credentials, never()).findByVerifierReference(fixture.credential.getVerifierReference());
        ManagementTokenIssuanceResult issued = fixture.service.exchangeControl(authentication.authenticationContext());

        assertTrue(issued.issued());
        assertEquals(ManagementTokenPurpose.CONTROL_ACCESS, issued.issuedToken().purpose());
        assertFalse(issued.issuedToken().toString().contains(issued.issuedToken().bearerToken()));
        assertFalse(issued.issuedToken().toString().contains(issued.issuedToken().tokenId()));
        assertFalse(issued.issuedToken().toString().contains(issued.issuedToken().tokenReference()));
        ArgumentCaptor<AuthorizationManagementTokenEntity> tokenCaptor =
                ArgumentCaptor.forClass(AuthorizationManagementTokenEntity.class);
        verify(fixture.tokens).save(tokenCaptor.capture());
        assertEquals("navi.management.v1", tokenCaptor.getValue().getAudience());
        assertEquals("ACTIVE", tokenCaptor.getValue().getStatus());
        assertNull(tokenCaptor.getValue().getSecurityActionNonce());

        TypedManagementAuthorizationResult wrongRoute = fixture.service.authorize(fixture.direct(WHOAMI_ROUTE, WHOAMI_ACTION));
        assertTrue(wrongRoute.allowed());
        assertReason(fixture.service.exchangeControl(wrongRoute.authenticationContext()),
                AuthorizationReasonCode.AUTHZ_CREDENTIAL_LANE_DENIED);
    }

    @Test
    void securityActionRequiresOneVerifierToReturnFullExactBinding() {
        ManagementSecurityActionBinding binding = binding(SECURITY_ACTION);

        Fixture noVerifier = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY, true, List.of());
        ManagementAuthenticationContext noVerifierContext = authenticated(noVerifier, SECURITY_ROUTE, SECURITY_ACTION);
        assertReason(noVerifier.service.authorizeSecurityAction(noVerifierContext,
                        new ManagementSecurityActionAuthorizationRequest(binding, OpaqueSecretMaterial.of("step"),
                                OpaqueSecretMaterial.of("approval"))),
                AuthorizationReasonCode.AUTHZ_STEP_UP_REQUIRED);

        ManagementStepUpVerifier stepOnly = verifier(binding,
                new ManagementStepUpVerificationResult(binding, true, false, null));
        ManagementStepUpVerifier approvalOnly = verifier(binding,
                new ManagementStepUpVerificationResult(binding, false, true, "approval-ref"));
        Fixture splitProof = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY, true, List.of(stepOnly, approvalOnly));
        assertReason(splitProof.service.authorizeSecurityAction(
                        authenticated(splitProof, SECURITY_ROUTE, SECURITY_ACTION),
                        new ManagementSecurityActionAuthorizationRequest(binding, OpaqueSecretMaterial.of("step"),
                                OpaqueSecretMaterial.of("approval"))),
                AuthorizationReasonCode.AUTHZ_STEP_UP_REQUIRED);

        ManagementSecurityActionBinding otherBinding = binding("resource.delete");
        Fixture mismatchedProof = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY, true,
                List.of(verifier(binding, new ManagementStepUpVerificationResult(otherBinding, true, true, "approval-ref"))));
        assertReason(mismatchedProof.service.authorizeSecurityAction(
                        authenticated(mismatchedProof, SECURITY_ROUTE, SECURITY_ACTION),
                        new ManagementSecurityActionAuthorizationRequest(binding, OpaqueSecretMaterial.of("step"),
                                OpaqueSecretMaterial.of("approval"))),
                AuthorizationReasonCode.AUTHZ_STEP_UP_REQUIRED);

        Fixture verified = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY, true,
                List.of(verifier(binding, new ManagementStepUpVerificationResult(binding, true, true, "approval-ref"))));
        ManagementTokenIssuanceResult issued = verified.service.authorizeSecurityAction(
                authenticated(verified, SECURITY_ROUTE, SECURITY_ACTION),
                new ManagementSecurityActionAuthorizationRequest(binding, OpaqueSecretMaterial.of("step"),
                        OpaqueSecretMaterial.of("approval")));
        assertTrue(issued.issued());
        assertEquals(ManagementTokenPurpose.SECURITY_ACTION, issued.issuedToken().purpose());
    }

    @Test
    void validatesButDoesNotConsumeSecurityTokensUntilTheExplicitCasBoundary() throws Exception {
        Fixture fixture = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY, true, List.of());
        ManagementSecurityActionBinding binding = binding(SECURITY_ACTION);
        fixture.installSecurityToken(binding);

        TypedManagementAuthorizationResult checked = fixture.service.authorize(fixture.bearer(SECURITY_ROUTE, SECURITY_ACTION));
        assertTrue(checked.allowed());
        verify(fixture.tokens, never()).consumeSecurityActionAtomically(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), any(), any());

        AtomicBoolean unconsumed = new AtomicBoolean(true);
        when(fixture.tokens.consumeSecurityActionAtomically(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), any(), any())).thenAnswer(invocation -> unconsumed.compareAndSet(true, false) ? 1 : 0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TypedManagementAuthorizationResult> first = executor.submit(
                    () -> fixture.service.consumeSecurityAction(fixture.bearer(SECURITY_ROUTE, SECURITY_ACTION), binding));
            Future<TypedManagementAuthorizationResult> second = executor.submit(
                    () -> fixture.service.consumeSecurityAction(fixture.bearer(SECURITY_ROUTE, SECURITY_ACTION), binding));
            TypedManagementAuthorizationResult firstResult = first.get();
            TypedManagementAuthorizationResult secondResult = second.get();

            assertEquals(1, List.of(firstResult, secondResult).stream().filter(TypedManagementAuthorizationResult::allowed).count());
            assertEquals(1, List.of(firstResult, secondResult).stream()
                    .filter(result -> result.reasonCode() == AuthorizationReasonCode.AUTHZ_ACTION_TOKEN_REPLAYED).count());
        } finally {
            executor.shutdownNow();
        }
        verify(fixture.tokens, times(2)).consumeSecurityActionAtomically(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void rejectsMismatchedSecurityActionWithoutCallingCas() {
        Fixture fixture = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY, true, List.of());
        fixture.installSecurityToken(binding(SECURITY_ACTION));

        TypedManagementAuthorizationResult result = fixture.service.consumeSecurityAction(
                fixture.bearer(SECURITY_ROUTE, SECURITY_ACTION), binding("resource.delete"));

        assertReason(result, AuthorizationReasonCode.AUTHZ_ACTION_TOKEN_MISMATCH);
        verify(fixture.tokens, never()).consumeSecurityActionAtomically(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void revalidatesInspectionAndMarksExplainAsNonBindingPreflight() {
        Fixture fixture = fixture(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, true, List.of());
        ManagementAuthenticationContext context = authenticated(fixture, WHOAMI_ROUTE, WHOAMI_ACTION);

        ManagementAuthenticationInspection inspection = fixture.service.inspect(context);
        assertNotNull(inspection.authenticationContext());
        assertTrue(inspection.authorityCeilingActions().contains("auth.exchange"));
        assertTrue(inspection.effectiveCredentialActions().contains(WHOAMI_ACTION));

        ManagementAuthorizationExplanation explanation = fixture.service.explain(context,
                new ManagementAuthorizationExplainRequest(WHOAMI_ROUTE, WHOAMI_ACTION, null));
        assertTrue(explanation.allowed());
        assertTrue(explanation.nonBinding());
        assertTrue(explanation.decision().nonBinding());
        assertEquals(AuthorizationEvaluationMode.PREFLIGHT, explanation.decision().evaluationMode());
        verify(fixture.tokens, never()).consumeSecurityActionAtomically(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), any(), any());

        fixture.credential.setStatus("REVOKED");
        assertNull(fixture.service.inspect(context).authenticationContext());
        ManagementAuthorizationExplanation revoked = fixture.service.explain(context,
                new ManagementAuthorizationExplainRequest(WHOAMI_ROUTE, WHOAMI_ACTION, null));
        assertFalse(revoked.allowed());
        assertEquals(AuthorizationReasonCode.AUTHN_CREDENTIAL_REVOKED, revoked.reasonCode());
        assertTrue(revoked.nonBinding());
    }

    private static ManagementAuthenticationContext authenticated(Fixture fixture, String routeId, String actionId) {
        TypedManagementAuthorizationResult result = fixture.service.authorize(fixture.direct(routeId, actionId));
        assertTrue(result.allowed(), () -> "expected fixture authentication but got " + result.reasonCode());
        return result.authenticationContext();
    }

    private static void assertReason(TypedManagementAuthorizationResult result, AuthorizationReasonCode expected) {
        assertFalse(result.allowed());
        assertEquals(expected, result.reasonCode());
        assertFalse(result.decision().nonBinding());
    }

    private static void assertReason(ManagementTokenIssuanceResult result, AuthorizationReasonCode expected) {
        assertFalse(result.issued());
        assertEquals(expected, result.reasonCode());
        assertFalse(result.decision().nonBinding());
    }

    private static ManagementSecurityActionBinding binding(String actionId) {
        return new ManagementSecurityActionBinding(actionId, "target-digest", "impact-digest", "reason-digest");
    }

    private static ManagementStepUpVerifier verifier(ManagementSecurityActionBinding supported,
                                                      ManagementStepUpVerificationResult result) {
        return new ManagementStepUpVerifier() {
            @Override
            public boolean supports(ManagementSecurityActionBinding actionBinding) {
                return supported.equals(actionBinding);
            }

            @Override
            public ManagementStepUpVerificationResult verify(ManagementStepUpVerificationRequest request) {
                return result;
            }
        };
    }

    private static Fixture fixture(AuthorizationCredentialLane lane,
                                   boolean withCredentialVerifier,
                                   List<ManagementStepUpVerifier> stepUpVerifiers) {
        Fixture fixture = new Fixture(lane);
        List<ManagementCredentialVerifier> credentialVerifiers = withCredentialVerifier
                ? List.of(fixture.credentialVerifier()) : List.of();
        fixture.service = new TypedManagementAuthorizationService(
                fixture.credentials,
                fixture.principals,
                fixture.tokens,
                () -> new DeploymentIdentity(fixture.instanceId, fixture.profile, DeploymentIdentitySource.CONFIGURED, false),
                fixture.registry,
                fixture.hasher,
                fixture.generator,
                fixture.codec,
                credentialVerifiers,
                stepUpVerifiers);
        return fixture;
    }

    private static final class Fixture {

        private final String instanceId = "fixture-instance";
        private final String profile = "fixture-local";
        private final AuthorizationCredentialRepository credentials = mock(AuthorizationCredentialRepository.class);
        private final AuthorizationPrincipalRepository principals = mock(AuthorizationPrincipalRepository.class);
        private final AuthorizationManagementTokenRepository tokens = mock(AuthorizationManagementTokenRepository.class);
        private final ManagementActionSetRegistry registry = new ManagementActionSetRegistry();
        private final OpaqueSecretHasher hasher = new OpaqueSecretHasher();
        private final OpaqueSecretGenerator generator = new OpaqueSecretGenerator();
        private final TypedManagementPresentationCodec codec = new TypedManagementPresentationCodec();
        private final OpaqueSecretMaterial credentialSecret = OpaqueSecretMaterial.of("fixture-principal-secret");
        private final AuthorizationCredentialEntity credential;
        private final AuthorizationPrincipalEntity principal;
        private final OpaqueSecretMaterial principalPresentation;
        private TypedManagementAuthorizationService service;
        private OpaqueSecretMaterial tokenPresentation;

        private Fixture(AuthorizationCredentialLane lane) {
            AuthorizationPrincipalType principalType = lane == AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL
                    || lane == AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY
                    ? AuthorizationPrincipalType.INSTANCE_ROOT : AuthorizationPrincipalType.SAAS_PLATFORM;
            credential = new AuthorizationCredentialEntity();
            credential.setCredentialId("fixture-credential");
            credential.setNavigatorInstanceId(instanceId);
            credential.setEnvironmentProfile(profile);
            credential.setPrincipalRecordId("fixture-principal-record");
            credential.setPrincipalId("fixture-principal");
            credential.setPrincipalType(principalType.name());
            credential.setCredentialLane(lane.name());
            credential.setVerifierReference("fixture-verifier-ref");
            credential.setCredentialFingerprint("fixture-fingerprint");
            credential.setGeneration(1);
            credential.setActionSetRef(registry.findByLane(lane).orElseThrow().actionSetRef());
            credential.setStatus("ACTIVE");
            credential.setExpiresAt(LocalDateTime.now().plusMinutes(10));

            principal = new AuthorizationPrincipalEntity();
            principal.setPrincipalRecordId(credential.getPrincipalRecordId());
            principal.setNavigatorInstanceId(instanceId);
            principal.setEnvironmentProfile(profile);
            principal.setPrincipalType(principalType.name());
            principal.setPrincipalId(credential.getPrincipalId());
            principal.setSourceUpstreamSystemId("fixture-upstream");
            principal.setUpstreamTrustProfile("fixture-trusted");
            principal.setStatus("ACTIVE");

            principalPresentation = OpaqueSecretMaterial.of(codec.encodePrincipalCredential(
                    credential.getVerifierReference(), credentialSecret));
            when(credentials.findByVerifierReferenceAndNavigatorInstanceIdAndEnvironmentProfile(
                    credential.getVerifierReference(), instanceId, profile)).thenReturn(Optional.of(credential));
            when(credentials.findByCredentialIdAndNavigatorInstanceIdAndEnvironmentProfile(
                    credential.getCredentialId(), instanceId, profile)).thenReturn(Optional.of(credential));
            when(principals.findByPrincipalRecordIdAndNavigatorInstanceIdAndEnvironmentProfileAndPrincipalTypeAndPrincipalIdAndStatus(
                    credential.getPrincipalRecordId(), instanceId, profile, credential.getPrincipalType(), credential.getPrincipalId(),
                    "ACTIVE")).thenReturn(Optional.of(principal));
            when(tokens.save(any(AuthorizationManagementTokenEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        }

        private ManagementCredentialVerifier credentialVerifier() {
            return new ManagementCredentialVerifier() {
                @Override
                public boolean supports(String verifierReference) {
                    return credential.getVerifierReference().equals(verifierReference);
                }

                @Override
                public boolean verify(ManagementCredentialVerificationRequest request) {
                    return credential.getVerifierReference().equals(request.verifierReference())
                            && hasher.hash(credentialSecret).equals(request.presentationHash());
                }
            };
        }

        private TypedManagementAuthenticationRequest direct(String routeId, String actionId) {
            return new TypedManagementAuthenticationRequest(routeId, actionId, "server-correlation", principalPresentation,
                    null, false);
        }

        private TypedManagementAuthenticationRequest bearer(String routeId, String actionId) {
            return new TypedManagementAuthenticationRequest(routeId, actionId, "server-correlation", null,
                    tokenPresentation, false);
        }

        private void installSecurityToken(ManagementSecurityActionBinding binding) {
            OpaqueSecretMaterial tokenSecret = OpaqueSecretMaterial.of("fixture-management-token-secret");
            String reference = "fixture-token-reference";
            AuthorizationManagementTokenEntity token = new AuthorizationManagementTokenEntity();
            token.setTokenId("fixture-token");
            token.setTokenReference(reference);
            token.setTokenHash(hasher.hash(tokenSecret));
            token.setCredentialId(credential.getCredentialId());
            token.setCredentialGeneration(credential.getGeneration());
            token.setNavigatorInstanceId(instanceId);
            token.setEnvironmentProfile(profile);
            token.setAudience("navi.management.v1");
            token.setPurpose(ManagementTokenPurpose.SECURITY_ACTION.name());
            token.setActionId(binding.actionId());
            token.setTargetDigest(binding.targetDigest());
            token.setImpactDigest(binding.impactDigest());
            token.setReasonDigest(binding.reasonDigest());
            token.setSecurityActionNonce("fixture-security-nonce");
            token.setStatus("ACTIVE");
            token.setExpiresAt(LocalDateTime.now().plusMinutes(5));
            tokenPresentation = OpaqueSecretMaterial.of(codec.encodeManagementToken(reference, tokenSecret));
            when(tokens.findByTokenHashAndTokenReferenceAndNavigatorInstanceIdAndEnvironmentProfile(
                    token.getTokenHash(), reference, instanceId, profile)).thenReturn(Optional.of(token));
        }
    }
}
