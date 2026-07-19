package com.foggy.navigator.common.authorization;

import com.foggy.navigator.common.entity.AuthorizationCredentialEntity;
import com.foggy.navigator.common.entity.AuthorizationManagementTokenEntity;
import com.foggy.navigator.common.entity.AuthorizationPrincipalEntity;
import com.foggy.navigator.common.repository.AuthorizationCredentialRepository;
import com.foggy.navigator.common.repository.AuthorizationManagementTokenRepository;
import com.foggy.navigator.common.repository.AuthorizationPrincipalRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Binding P1B management authentication implementation. It is intentionally
 * independent of legacy interceptors and the P1A shadow evaluator. With no
 * typed verifier/fixture, every credential presentation is denied.
 */
@Component
public class TypedManagementAuthorizationService implements TypedManagementIngressAuthorizer,
        TypedManagementTokenIssuer, TypedManagementAuthenticationInspector,
        TypedManagementAuthorizationExplainer {

    static final String ACTIVE = "ACTIVE";
    static final String REVOKED = "REVOKED";
    static final String CONSUMED = "CONSUMED";
    static final String MANAGEMENT_AUDIENCE = "navi.management.v1";
    static final int CONTROL_ACCESS_MINUTES = 15;
    static final int SECURITY_ACTION_MINUTES = 5;

    private final AuthorizationCredentialRepository credentialRepository;
    private final AuthorizationPrincipalRepository principalRepository;
    private final AuthorizationManagementTokenRepository tokenRepository;
    private final DeploymentIdentityProvider deploymentIdentityProvider;
    private final ManagementActionSetRegistry actionSetRegistry;
    private final OpaqueSecretHasher secretHasher;
    private final OpaqueSecretGenerator secretGenerator;
    private final TypedManagementPresentationCodec presentationCodec;
    private final List<ManagementCredentialVerifier> credentialVerifiers;
    private final List<ManagementStepUpVerifier> stepUpVerifiers;

    public TypedManagementAuthorizationService(
            AuthorizationCredentialRepository credentialRepository,
            AuthorizationPrincipalRepository principalRepository,
            AuthorizationManagementTokenRepository tokenRepository,
            DeploymentIdentityProvider deploymentIdentityProvider,
            ManagementActionSetRegistry actionSetRegistry,
            OpaqueSecretHasher secretHasher,
            OpaqueSecretGenerator secretGenerator,
            TypedManagementPresentationCodec presentationCodec,
            List<ManagementCredentialVerifier> credentialVerifiers,
            List<ManagementStepUpVerifier> stepUpVerifiers
    ) {
        this.credentialRepository = credentialRepository;
        this.principalRepository = principalRepository;
        this.tokenRepository = tokenRepository;
        this.deploymentIdentityProvider = deploymentIdentityProvider;
        this.actionSetRegistry = actionSetRegistry;
        this.secretHasher = secretHasher;
        this.secretGenerator = secretGenerator;
        this.presentationCodec = presentationCodec;
        this.credentialVerifiers = List.copyOf(credentialVerifiers);
        this.stepUpVerifiers = List.copyOf(stepUpVerifiers);
    }

    @Override
    public TypedManagementAuthorizationResult authorize(TypedManagementAuthenticationRequest request) {
        ResolvedAuthentication resolved = resolve(request);
        if (!resolved.allowed()) {
            return denied(request, resolved.source(), null, resolved.reasonCode());
        }
        ManagementAuthenticationContext context = resolved.authenticationContext();
        if (!actionSetRegistry.isRegisteredEndpointAction(context.routeId(), context.actionId())) {
            return denied(request, resolved.source(), context, AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
        }
        if (!actionSetRegistry.allows(context.actionSetRef(), context.actionId())) {
            return denied(request, resolved.source(), context, AuthorizationReasonCode.AUTHZ_ACTION_DENIED);
        }
        return allowed(resolved.source(), context);
    }

    /**
     * Unlike {@link #authorize(TypedManagementAuthenticationRequest)}, this is
     * a mutation boundary: only this method consumes SECURITY_ACTION tokens.
     */
    @Override
    @Transactional
    public TypedManagementAuthorizationResult consumeSecurityAction(
            TypedManagementAuthenticationRequest request,
            ManagementSecurityActionBinding binding
    ) {
        ResolvedAuthentication resolved = resolve(request);
        if (!resolved.allowed()) {
            return denied(request, resolved.source(), null, resolved.reasonCode());
        }
        if (resolved.token() == null || resolved.tokenPurpose() != ManagementTokenPurpose.SECURITY_ACTION) {
            return denied(request, resolved.source(), resolved.authenticationContext(),
                    AuthorizationReasonCode.AUTHZ_ACTION_TOKEN_MISMATCH);
        }
        if (binding == null || !binding.complete()
                || !actionSetRegistry.isRegisteredEndpointAction(
                resolved.authenticationContext().routeId(), resolved.authenticationContext().actionId())
                || !same(resolved.authenticationContext().actionId(), binding.actionId())
                || !same(resolved.token().getActionId(), binding.actionId())
                || !same(resolved.token().getTargetDigest(), binding.targetDigest())
                || !same(resolved.token().getImpactDigest(), binding.impactDigest())
                || !same(resolved.token().getReasonDigest(), binding.reasonDigest())
                || !actionSetRegistry.allows(resolved.authenticationContext().actionSetRef(), binding.actionId())) {
            return denied(request, resolved.source(), resolved.authenticationContext(),
                    AuthorizationReasonCode.AUTHZ_ACTION_TOKEN_MISMATCH);
        }
        LocalDateTime now = LocalDateTime.now();
        int consumed = tokenRepository.consumeSecurityActionAtomically(
                resolved.token().getTokenId(),
                resolved.tokenHash(),
                resolved.tokenReference(),
                resolved.authenticationContext().navigatorInstanceId(),
                resolved.authenticationContext().environmentProfile(),
                ManagementTokenPurpose.SECURITY_ACTION.name(),
                resolved.authenticationContext().credentialGeneration(),
                ACTIVE,
                CONSUMED,
                now,
                now
        );
        if (consumed != 1) {
            return denied(request, resolved.source(), resolved.authenticationContext(),
                    AuthorizationReasonCode.AUTHZ_ACTION_TOKEN_REPLAYED);
        }
        return allowed(resolved.source(), resolved.authenticationContext());
    }

    @Override
    @Transactional
    public ManagementTokenIssuanceResult exchangeControl(ManagementAuthenticationContext authenticationContext) {
        ContextRevalidation revalidation = revalidateContext(authenticationContext);
        if (!revalidation.allowed()) {
            return issuanceDenied(authenticationContext, revalidation.reasonCode());
        }
        ManagementAuthenticationContext context = revalidation.authenticationContext();
        if (context.credentialSource() != TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL
                || (context.credentialLane() != AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL
                && context.credentialLane() != AuthorizationCredentialLane.SAAS_PROVISIONING)
                || !actionSetRegistry.isRegisteredEndpointAction(context.routeId(), context.actionId())
                || !"auth.exchange".equals(context.actionId())) {
            return issuanceDenied(context, AuthorizationReasonCode.AUTHZ_CREDENTIAL_LANE_DENIED);
        }
        return issueToken(context, ManagementTokenPurpose.CONTROL_ACCESS, null, null);
    }

    @Override
    @Transactional
    public ManagementTokenIssuanceResult authorizeSecurityAction(
            ManagementAuthenticationContext authenticationContext,
            ManagementSecurityActionAuthorizationRequest authorizationRequest
    ) {
        ContextRevalidation revalidation = revalidateContext(authenticationContext);
        if (!revalidation.allowed()) {
            return issuanceDenied(authenticationContext, revalidation.reasonCode());
        }
        ManagementAuthenticationContext context = revalidation.authenticationContext();
        if (context.credentialSource() != TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL
                || (context.credentialLane() != AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY
                && context.credentialLane() != AuthorizationCredentialLane.SAAS_SECURITY_ADMIN)
                || !actionSetRegistry.isRegisteredEndpointAction(context.routeId(), context.actionId())
                || !"auth.security-authorize".equals(context.actionId())) {
            return issuanceDenied(context, AuthorizationReasonCode.AUTHZ_CREDENTIAL_LANE_DENIED);
        }
        if (authorizationRequest == null || authorizationRequest.actionBinding() == null
                || !authorizationRequest.actionBinding().complete()
                || !actionSetRegistry.allows(context.actionSetRef(), authorizationRequest.actionBinding().actionId())) {
            return issuanceDenied(context, AuthorizationReasonCode.AUTHZ_ACTION_DENIED);
        }
        ManagementStepUpVerificationResult verification = verifyStepUp(context, authorizationRequest);
        if (!verification.stepUpSatisfied()) {
            return issuanceDenied(context, AuthorizationReasonCode.AUTHZ_STEP_UP_REQUIRED);
        }
        if (!verification.approvalSatisfied() || blank(verification.approvalReference())) {
            return issuanceDenied(context, AuthorizationReasonCode.AUTHZ_APPROVAL_REQUIRED);
        }
        return issueToken(context, ManagementTokenPurpose.SECURITY_ACTION,
                authorizationRequest.actionBinding(), verification.approvalReference());
    }

    @Override
    public ManagementAuthenticationInspection inspect(ManagementAuthenticationContext authenticationContext) {
        ContextRevalidation revalidation = revalidateContext(authenticationContext);
        if (!revalidation.allowed()
                || !actionSetRegistry.matches(revalidation.authenticationContext().principalType(),
                revalidation.authenticationContext().credentialLane(),
                revalidation.authenticationContext().actionSetRef())) {
            return new ManagementAuthenticationInspection(null, java.util.Set.of(), java.util.Set.of());
        }
        ManagementAuthenticationContext context = revalidation.authenticationContext();
        return new ManagementAuthenticationInspection(
                context,
                actionSetRegistry.authorityCeilingActions(context.principalType()),
                actionSetRegistry.effectiveActions(context.actionSetRef())
        );
    }

    @Override
    public ManagementAuthorizationExplanation explain(
            ManagementAuthenticationContext authenticationContext,
            ManagementAuthorizationExplainRequest request
    ) {
        ContextRevalidation revalidation = revalidateContext(authenticationContext);
        if (!revalidation.allowed()) {
            return explanation(null, request, AuthorizationDecisionOutcome.DENY, revalidation.reasonCode());
        }
        ManagementAuthenticationContext context = revalidation.authenticationContext();
        if (request == null
                || !actionSetRegistry.matches(context.principalType(), context.credentialLane(),
                context.actionSetRef())
                || !actionSetRegistry.isRegisteredEndpointAction(request.routeId(), request.actionId())) {
            return explanation(context, request, AuthorizationDecisionOutcome.DENY,
                    AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
        }
        if (!actionSetRegistry.allows(context.actionSetRef(), request.actionId())
                || (request.actionBinding() != null && (!request.actionBinding().complete()
                || !request.actionId().equals(request.actionBinding().actionId())))) {
            return explanation(context, request, AuthorizationDecisionOutcome.DENY,
                    AuthorizationReasonCode.AUTHZ_ACTION_DENIED);
        }
        return explanation(context, request, AuthorizationDecisionOutcome.ALLOW, null);
    }

    private ResolvedAuthentication resolve(TypedManagementAuthenticationRequest request) {
        if (request == null) {
            return ResolvedAuthentication.denied(TypedManagementCredentialSource.NONE,
                    AuthorizationReasonCode.AUTHN_CREDENTIAL_MISSING);
        }
        int typedSourceCount = (request.principalCredential() == null ? 0 : 1)
                + (request.managementBearer() == null ? 0 : 1);
        if (request.prohibitedCredentialSourcePresent() || typedSourceCount > 1) {
            return ResolvedAuthentication.denied(TypedManagementCredentialSource.NONE,
                    AuthorizationReasonCode.AUTHN_CREDENTIAL_CONFLICT);
        }
        if (request.malformedTypedCredentialPresentation()) {
            return ResolvedAuthentication.denied(TypedManagementCredentialSource.NONE,
                    AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        if (typedSourceCount == 0) {
            return ResolvedAuthentication.denied(TypedManagementCredentialSource.NONE,
                    AuthorizationReasonCode.AUTHN_CREDENTIAL_MISSING);
        }
        if (request.principalCredential() != null) {
            return resolvePrincipalCredential(request);
        }
        return resolveManagementBearer(request);
    }

    private ResolvedAuthentication resolvePrincipalCredential(TypedManagementAuthenticationRequest request) {
        Optional<TypedManagementPresentationCodec.DecodedPresentation> decoded =
                presentationCodec.decodePrincipalCredential(request.principalCredential());
        if (decoded.isEmpty()) {
            return ResolvedAuthentication.denied(TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                    AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        DeploymentIdentity deployment = deploymentIdentityProvider.deploymentIdentity();
        Optional<AuthorizationCredentialEntity> credential = credentialRepository
                .findByVerifierReferenceAndNavigatorInstanceIdAndEnvironmentProfile(
                        decoded.get().reference(), deployment.navigatorInstanceId(), deployment.environmentProfile());
        if (credential.isEmpty()) {
            return ResolvedAuthentication.denied(TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL,
                    credentialScopeMismatch(decoded.get().reference(), deployment));
        }
        AuthorizationReasonCode verifierFailure = verifyCredential(decoded.get().reference(), decoded.get().secret());
        if (verifierFailure != null) {
            return ResolvedAuthentication.denied(TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL, verifierFailure);
        }
        return resolveStoredCredential(credential.get(), deployment,
                TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL, null,
                request.routeId(), request.actionId(), correlationId(request.correlationId()));
    }

    private ResolvedAuthentication resolveManagementBearer(TypedManagementAuthenticationRequest request) {
        Optional<TypedManagementPresentationCodec.DecodedPresentation> decoded =
                presentationCodec.decodeManagementToken(request.managementBearer());
        if (decoded.isEmpty()) {
            return ResolvedAuthentication.denied(TypedManagementCredentialSource.MANAGEMENT_BEARER,
                    AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        DeploymentIdentity deployment = deploymentIdentityProvider.deploymentIdentity();
        String tokenHash = secretHasher.hash(decoded.get().secret());
        Optional<AuthorizationManagementTokenEntity> token = tokenRepository
                .findByTokenHashAndTokenReferenceAndNavigatorInstanceIdAndEnvironmentProfile(
                        tokenHash, decoded.get().reference(), deployment.navigatorInstanceId(), deployment.environmentProfile());
        if (token.isEmpty()) {
            return ResolvedAuthentication.denied(TypedManagementCredentialSource.MANAGEMENT_BEARER,
                    tokenScopeMismatch(tokenHash, decoded.get().reference(), deployment));
        }
        AuthorizationManagementTokenEntity tokenEntity = token.get();
        AuthorizationReasonCode tokenFailure = validateTokenEnvelope(tokenEntity, deployment);
        if (tokenFailure != null) {
            return ResolvedAuthentication.denied(TypedManagementCredentialSource.MANAGEMENT_BEARER, tokenFailure);
        }
        ManagementTokenPurpose purpose = parsePurpose(tokenEntity.getPurpose());
        ResolvedAuthentication credentialResolution = revalidateTokenCredential(tokenEntity, deployment,
                request.routeId(), request.actionId(), correlationId(request.correlationId()), purpose);
        if (!credentialResolution.allowed()) {
            return credentialResolution.withSource(TypedManagementCredentialSource.MANAGEMENT_BEARER);
        }
        return credentialResolution.withToken(tokenEntity, tokenHash, decoded.get().reference(), purpose);
    }

    private ResolvedAuthentication revalidateTokenCredential(AuthorizationManagementTokenEntity token,
                                                              DeploymentIdentity deployment,
                                                              String routeId,
                                                              String actionId,
                                                              String correlationId,
                                                              ManagementTokenPurpose purpose) {
        Optional<AuthorizationCredentialEntity> credential = credentialRepository
                .findByCredentialIdAndNavigatorInstanceIdAndEnvironmentProfile(
                        token.getCredentialId(), deployment.navigatorInstanceId(), deployment.environmentProfile());
        if (credential.isEmpty()) {
            return ResolvedAuthentication.denied(TypedManagementCredentialSource.MANAGEMENT_BEARER,
                    AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        if (!sameInteger(token.getCredentialGeneration(), credential.get().getGeneration())) {
            return ResolvedAuthentication.denied(TypedManagementCredentialSource.MANAGEMENT_BEARER,
                    AuthorizationReasonCode.AUTHN_CREDENTIAL_GENERATION_MISMATCH);
        }
        return resolveStoredCredential(credential.get(), deployment, TypedManagementCredentialSource.MANAGEMENT_BEARER,
                purpose, routeId, actionId, correlationId);
    }

    private ResolvedAuthentication resolveStoredCredential(AuthorizationCredentialEntity credential,
                                                            DeploymentIdentity deployment,
                                                            TypedManagementCredentialSource source,
                                                            ManagementTokenPurpose tokenPurpose,
                                                            String routeId,
                                                            String actionId,
                                                            String correlationId) {
        AuthorizationReasonCode credentialFailure = validateCredential(credential, deployment);
        if (credentialFailure != null) {
            return ResolvedAuthentication.denied(source, credentialFailure);
        }
        AuthorizationPrincipalType principalType = parsePrincipalType(credential.getPrincipalType());
        if (principalType == AuthorizationPrincipalType.UNKNOWN) {
            return ResolvedAuthentication.denied(source, AuthorizationReasonCode.AUTHZ_PRINCIPAL_TYPE_DENIED);
        }
        AuthorizationCredentialLane lane = parseCredentialLane(credential.getCredentialLane());
        if (lane == AuthorizationCredentialLane.UNKNOWN) {
            return ResolvedAuthentication.denied(source, AuthorizationReasonCode.AUTHZ_CREDENTIAL_LANE_DENIED);
        }
        if (!actionSetRegistry.matches(principalType, lane, credential.getActionSetRef())) {
            return ResolvedAuthentication.denied(source, AuthorizationReasonCode.AUTHZ_CREDENTIAL_LANE_DENIED);
        }
        Optional<AuthorizationPrincipalEntity> principal = principalRepository
                .findByPrincipalRecordIdAndNavigatorInstanceIdAndEnvironmentProfileAndPrincipalTypeAndPrincipalIdAndStatus(
                        credential.getPrincipalRecordId(), deployment.navigatorInstanceId(), deployment.environmentProfile(),
                        credential.getPrincipalType(), credential.getPrincipalId(), ACTIVE);
        if (principal.isEmpty()) {
            return ResolvedAuthentication.denied(source, AuthorizationReasonCode.AUTHZ_PRINCIPAL_TYPE_DENIED);
        }
        AuthorizationPrincipalEntity principalEntity = principal.get();
        if (blank(principalEntity.getUpstreamTrustProfile())) {
            return ResolvedAuthentication.denied(source, AuthorizationReasonCode.AUTHZ_TRUST_PROFILE_UNKNOWN);
        }
        ManagementAuthenticationContext context = new ManagementAuthenticationContext(
                principalEntity.getPrincipalRecordId(),
                principalType,
                principalEntity.getPrincipalId(),
                principalEntity.getSourceUpstreamSystemId(),
                deployment.navigatorInstanceId(),
                deployment.environmentProfile(),
                principalEntity.getUpstreamTrustProfile(),
                credential.getCredentialId(),
                lane,
                credential.getCredentialFingerprint(),
                credential.getGeneration(),
                credential.getActionSetRef(),
                credential.getStatus(),
                toInstant(credential.getExpiresAt()),
                source,
                tokenPurpose,
                routeId,
                actionId,
                correlationId
        );
        return ResolvedAuthentication.allowed(source, context);
    }

    private AuthorizationReasonCode verifyCredential(String verifierReference, OpaqueSecretMaterial presentation) {
        List<ManagementCredentialVerifier> supporting = credentialVerifiers.stream()
                .filter(verifier -> supports(verifier, verifierReference))
                .toList();
        if (supporting.size() != 1) {
            return AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID;
        }
        try {
            boolean verified = supporting.get(0).verify(new ManagementCredentialVerificationRequest(
                    verifierReference, secretHasher.hash(presentation)));
            return verified ? null : AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID;
        } catch (RuntimeException exception) {
            return AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID;
        }
    }

    private boolean supports(ManagementCredentialVerifier verifier, String verifierReference) {
        try {
            return verifier.supports(verifierReference);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private AuthorizationReasonCode validateCredential(AuthorizationCredentialEntity credential,
                                                        DeploymentIdentity deployment) {
        if (!same(credential.getNavigatorInstanceId(), deployment.navigatorInstanceId())) {
            return AuthorizationReasonCode.AUTHZ_INSTANCE_MISMATCH;
        }
        if (!same(credential.getEnvironmentProfile(), deployment.environmentProfile())) {
            return AuthorizationReasonCode.AUTHZ_ENVIRONMENT_MISMATCH;
        }
        if (REVOKED.equals(credential.getStatus())) {
            return AuthorizationReasonCode.AUTHN_CREDENTIAL_REVOKED;
        }
        if (!ACTIVE.equals(credential.getStatus())) {
            return AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID;
        }
        if (credential.getExpiresAt() == null || !credential.getExpiresAt().isAfter(LocalDateTime.now())) {
            return AuthorizationReasonCode.AUTHN_CREDENTIAL_EXPIRED;
        }
        if (credential.getGeneration() == null || credential.getGeneration() < 1) {
            return AuthorizationReasonCode.AUTHN_CREDENTIAL_GENERATION_MISMATCH;
        }
        if (blank(credential.getPrincipalRecordId()) || blank(credential.getPrincipalId())
                || blank(credential.getCredentialFingerprint()) || blank(credential.getActionSetRef())) {
            return AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID;
        }
        return null;
    }

    private AuthorizationReasonCode validateTokenEnvelope(AuthorizationManagementTokenEntity token,
                                                           DeploymentIdentity deployment) {
        if (!same(token.getNavigatorInstanceId(), deployment.navigatorInstanceId())) {
            return AuthorizationReasonCode.AUTHZ_INSTANCE_MISMATCH;
        }
        if (!same(token.getEnvironmentProfile(), deployment.environmentProfile())) {
            return AuthorizationReasonCode.AUTHZ_ENVIRONMENT_MISMATCH;
        }
        if (!MANAGEMENT_AUDIENCE.equals(token.getAudience())) {
            return AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID;
        }
        ManagementTokenPurpose purpose = parsePurpose(token.getPurpose());
        if (purpose == ManagementTokenPurpose.UNKNOWN) {
            return AuthorizationReasonCode.AUTHZ_CREDENTIAL_LANE_DENIED;
        }
        if (purpose == ManagementTokenPurpose.SECURITY_ACTION && CONSUMED.equals(token.getStatus())) {
            return AuthorizationReasonCode.AUTHZ_ACTION_TOKEN_REPLAYED;
        }
        if (REVOKED.equals(token.getStatus())) {
            return AuthorizationReasonCode.AUTHN_CREDENTIAL_REVOKED;
        }
        if (!ACTIVE.equals(token.getStatus())) {
            return AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID;
        }
        if (token.getExpiresAt() == null || !token.getExpiresAt().isAfter(LocalDateTime.now())) {
            return AuthorizationReasonCode.AUTHN_CREDENTIAL_EXPIRED;
        }
        if (token.getCredentialGeneration() == null || token.getCredentialGeneration() < 1
                || blank(token.getCredentialId()) || blank(token.getTokenReference()) || blank(token.getTokenHash())) {
            return AuthorizationReasonCode.AUTHN_CREDENTIAL_GENERATION_MISMATCH;
        }
        if (purpose == ManagementTokenPurpose.SECURITY_ACTION
                && (blank(token.getActionId()) || blank(token.getTargetDigest()) || blank(token.getImpactDigest())
                || blank(token.getReasonDigest()) || blank(token.getSecurityActionNonce()))) {
            return AuthorizationReasonCode.AUTHZ_ACTION_TOKEN_MISMATCH;
        }
        return null;
    }

    private ContextRevalidation revalidateContext(ManagementAuthenticationContext context) {
        if (context == null || context.principalType() == null || context.credentialLane() == null
                || blank(context.credentialId()) || blank(context.navigatorInstanceId())
                || blank(context.environmentProfile()) || blank(context.principalRecordId()) || blank(context.principalId())
                || (context.credentialSource() != TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL
                && context.credentialSource() != TypedManagementCredentialSource.MANAGEMENT_BEARER)
                || (context.credentialSource() == TypedManagementCredentialSource.PRINCIPAL_CREDENTIAL
                && context.tokenPurpose() != null)
                || (context.credentialSource() == TypedManagementCredentialSource.MANAGEMENT_BEARER
                && (context.tokenPurpose() == null || context.tokenPurpose() == ManagementTokenPurpose.UNKNOWN))) {
            return ContextRevalidation.denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        DeploymentIdentity deployment = deploymentIdentityProvider.deploymentIdentity();
        if (!same(context.navigatorInstanceId(), deployment.navigatorInstanceId())) {
            return ContextRevalidation.denied(AuthorizationReasonCode.AUTHZ_INSTANCE_MISMATCH);
        }
        if (!same(context.environmentProfile(), deployment.environmentProfile())) {
            return ContextRevalidation.denied(AuthorizationReasonCode.AUTHZ_ENVIRONMENT_MISMATCH);
        }
        Optional<AuthorizationCredentialEntity> credential = credentialRepository
                .findByCredentialIdAndNavigatorInstanceIdAndEnvironmentProfile(
                        context.credentialId(), deployment.navigatorInstanceId(), deployment.environmentProfile());
        if (credential.isEmpty()) {
            return ContextRevalidation.denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        if (!sameInteger(context.credentialGeneration(), credential.get().getGeneration())) {
            return ContextRevalidation.denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_GENERATION_MISMATCH);
        }
        ResolvedAuthentication revalidated = resolveStoredCredential(credential.get(), deployment,
                context.credentialSource(), context.tokenPurpose(), context.routeId(), context.actionId(), context.correlationId());
        if (!revalidated.allowed()) {
            return ContextRevalidation.denied(revalidated.reasonCode());
        }
        ManagementAuthenticationContext resolved = revalidated.authenticationContext();
        if (resolved.principalType() != context.principalType()
                || resolved.credentialLane() != context.credentialLane()
                || !same(resolved.principalRecordId(), context.principalRecordId())
                || !same(resolved.principalId(), context.principalId())
                || !same(resolved.actionSetRef(), context.actionSetRef())
                || !same(resolved.credentialFingerprint(), context.credentialFingerprint())) {
            return ContextRevalidation.denied(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
        }
        return ContextRevalidation.allowed(resolved);
    }

    private ManagementStepUpVerificationResult verifyStepUp(ManagementAuthenticationContext context,
                                                             ManagementSecurityActionAuthorizationRequest request) {
        ManagementStepUpVerificationRequest verificationRequest = new ManagementStepUpVerificationRequest(
                context, request.actionBinding(), request.stepUpProof(), request.approvalProof(), context.correlationId());
        for (ManagementStepUpVerifier verifier : stepUpVerifiers) {
            try {
                if (!verifier.supports(request.actionBinding())) {
                    continue;
                }
                ManagementStepUpVerificationResult result = verifier.verify(verificationRequest);
                if (result == null || !sameBinding(result.actionBinding(), request.actionBinding())) {
                    continue;
                }
                // Step-up and approval must come from one verifier for the
                // same exact action/target/impact/reason binding. Do not OR
                // independent partial results: that would permit proof
                // stitching across verifiers.
                if (result.stepUpSatisfied() && result.approvalSatisfied()
                        && !blank(result.approvalReference())) {
                    return result;
                }
            } catch (RuntimeException ignored) {
                // A verifier failure is a deny; it must not become a fallback path.
                return ManagementStepUpVerificationResult.denied(request.actionBinding());
            }
        }
        return ManagementStepUpVerificationResult.denied(request.actionBinding());
    }

    private ManagementTokenIssuanceResult issueToken(ManagementAuthenticationContext context,
                                                      ManagementTokenPurpose purpose,
                                                      ManagementSecurityActionBinding binding,
                                                      String approvalReference) {
        LocalDateTime issuedAt = LocalDateTime.now();
        LocalDateTime expiresAt = issuedAt.plusMinutes(purpose == ManagementTokenPurpose.CONTROL_ACCESS
                ? CONTROL_ACCESS_MINUTES : SECURITY_ACTION_MINUTES);
        OpaqueSecretMaterial secret = secretGenerator.generateSecret();
        String tokenReference = secretGenerator.generateReference();
        AuthorizationManagementTokenEntity token = new AuthorizationManagementTokenEntity();
        token.setTokenId(UUID.randomUUID().toString());
        token.setTokenReference(tokenReference);
        token.setTokenHash(secretHasher.hash(secret));
        token.setCredentialId(context.credentialId());
        token.setCredentialGeneration(context.credentialGeneration());
        token.setNavigatorInstanceId(context.navigatorInstanceId());
        token.setEnvironmentProfile(context.environmentProfile());
        token.setAudience(MANAGEMENT_AUDIENCE);
        token.setPurpose(purpose.name());
        token.setActionId(binding == null ? null : binding.actionId());
        token.setTargetDigest(binding == null ? null : binding.targetDigest());
        token.setImpactDigest(binding == null ? null : binding.impactDigest());
        token.setReasonDigest(binding == null ? null : binding.reasonDigest());
        token.setApprovalReference(approvalReference);
        token.setSecurityActionNonce(purpose == ManagementTokenPurpose.SECURITY_ACTION
                ? secretGenerator.generateReference() : null);
        token.setStatus(ACTIVE);
        token.setIssuedAt(issuedAt);
        token.setExpiresAt(expiresAt);
        tokenRepository.save(token);
        String bearer = presentationCodec.encodeManagementToken(tokenReference, secret);
        IssuedManagementToken issued = new IssuedManagementToken(
                bearer, token.getTokenId(), tokenReference, purpose, toInstant(expiresAt));
        PolicyDecisionV1 decision = decision(context.correlationId(), context.actionId(), context.routeId(),
                AuthorizationEvaluationMode.ENFORCEMENT, AuthorizationDecisionOutcome.ALLOW, null, false);
        return new ManagementTokenIssuanceResult(true, null, context, issued, decision);
    }

    private ManagementTokenIssuanceResult issuanceDenied(ManagementAuthenticationContext context,
                                                          AuthorizationReasonCode reasonCode) {
        String correlationId = context == null ? null : context.correlationId();
        String actionId = context == null ? null : context.actionId();
        String routeId = context == null ? null : context.routeId();
        return new ManagementTokenIssuanceResult(false, reasonCode, context, null,
                decision(correlationId, actionId, routeId, AuthorizationEvaluationMode.ENFORCEMENT,
                        AuthorizationDecisionOutcome.DENY, reasonCode, false));
    }

    private TypedManagementAuthorizationResult allowed(TypedManagementCredentialSource source,
                                                       ManagementAuthenticationContext context) {
        return new TypedManagementAuthorizationResult(true, null, source, context,
                decision(context.correlationId(), context.actionId(), context.routeId(),
                        AuthorizationEvaluationMode.ENFORCEMENT, AuthorizationDecisionOutcome.ALLOW, null, false));
    }

    private TypedManagementAuthorizationResult denied(TypedManagementAuthenticationRequest request,
                                                      TypedManagementCredentialSource source,
                                                      ManagementAuthenticationContext context,
                                                      AuthorizationReasonCode reasonCode) {
        String correlation = context != null ? context.correlationId() : request == null ? null : request.correlationId();
        String action = context != null ? context.actionId() : request == null ? null : request.actionId();
        String route = context != null ? context.routeId() : request == null ? null : request.routeId();
        return new TypedManagementAuthorizationResult(false, reasonCode, source, context,
                decision(correlation, action, route, AuthorizationEvaluationMode.ENFORCEMENT,
                        AuthorizationDecisionOutcome.DENY, reasonCode, false));
    }

    private ManagementAuthorizationExplanation explanation(ManagementAuthenticationContext context,
                                                            ManagementAuthorizationExplainRequest request,
                                                            AuthorizationDecisionOutcome outcome,
                                                            AuthorizationReasonCode reasonCode) {
        String correlation = context == null ? null : context.correlationId();
        String action = request == null ? null : request.actionId();
        String route = request == null ? null : request.routeId();
        PolicyDecisionV1 decision = decision(correlation, action, route, AuthorizationEvaluationMode.PREFLIGHT,
                outcome, reasonCode, true);
        return new ManagementAuthorizationExplanation(outcome == AuthorizationDecisionOutcome.ALLOW,
                reasonCode, true, decision);
    }

    private PolicyDecisionV1 decision(String correlationId,
                                      String actionId,
                                      String routeId,
                                      AuthorizationEvaluationMode mode,
                                      AuthorizationDecisionOutcome outcome,
                                      AuthorizationReasonCode reasonCode,
                                      boolean nonBinding) {
        return new PolicyDecisionV1(
                AuthorizationSchemaV1.SCHEMA_VERSION,
                AuthorizationSchemaV1.POLICY_VERSION,
                AuthorizationSchemaV1.ACTION_CATALOG_VERSION,
                AuthorizationSchemaV1.UNKNOWN_SERVER_BUILD,
                UUID.randomUUID().toString(),
                correlationId(correlationId),
                mode,
                outcome,
                reasonCode,
                nonBinding,
                actionId,
                routeId,
                Instant.now()
        );
    }

    private AuthorizationReasonCode credentialScopeMismatch(String verifierReference, DeploymentIdentity deployment) {
        return credentialRepository.findByVerifierReference(verifierReference)
                .map(credential -> !same(credential.getNavigatorInstanceId(), deployment.navigatorInstanceId())
                        ? AuthorizationReasonCode.AUTHZ_INSTANCE_MISMATCH
                        : !same(credential.getEnvironmentProfile(), deployment.environmentProfile())
                        ? AuthorizationReasonCode.AUTHZ_ENVIRONMENT_MISMATCH
                        : AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID)
                .orElse(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
    }

    private AuthorizationReasonCode tokenScopeMismatch(String tokenHash,
                                                        String tokenReference,
                                                        DeploymentIdentity deployment) {
        return tokenRepository.findByTokenHash(tokenHash)
                .map(token -> !same(token.getTokenReference(), tokenReference)
                        ? AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID
                        : !same(token.getNavigatorInstanceId(), deployment.navigatorInstanceId())
                        ? AuthorizationReasonCode.AUTHZ_INSTANCE_MISMATCH
                        : !same(token.getEnvironmentProfile(), deployment.environmentProfile())
                        ? AuthorizationReasonCode.AUTHZ_ENVIRONMENT_MISMATCH
                        : AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID)
                .orElse(AuthorizationReasonCode.AUTHN_CREDENTIAL_INVALID);
    }

    private static AuthorizationPrincipalType parsePrincipalType(String value) {
        if (value == null) {
            return AuthorizationPrincipalType.UNKNOWN;
        }
        try {
            return AuthorizationPrincipalType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return AuthorizationPrincipalType.UNKNOWN;
        }
    }

    private static AuthorizationCredentialLane parseCredentialLane(String value) {
        if (value == null) {
            return AuthorizationCredentialLane.UNKNOWN;
        }
        try {
            return AuthorizationCredentialLane.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return AuthorizationCredentialLane.UNKNOWN;
        }
    }

    private static ManagementTokenPurpose parsePurpose(String value) {
        if (value == null) {
            return ManagementTokenPurpose.UNKNOWN;
        }
        try {
            ManagementTokenPurpose purpose = ManagementTokenPurpose.valueOf(value);
            return purpose == ManagementTokenPurpose.CONTROL_ACCESS || purpose == ManagementTokenPurpose.SECURITY_ACTION
                    ? purpose : ManagementTokenPurpose.UNKNOWN;
        } catch (IllegalArgumentException exception) {
            return ManagementTokenPurpose.UNKNOWN;
        }
    }

    private static boolean same(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean sameInteger(Integer left, Integer right) {
        return left != null && left.equals(right);
    }

    private static boolean sameBinding(ManagementSecurityActionBinding left,
                                       ManagementSecurityActionBinding right) {
        return left != null && right != null
                && same(left.actionId(), right.actionId())
                && same(left.targetDigest(), right.targetDigest())
                && same(left.impactDigest(), right.impactDigest())
                && same(left.reasonDigest(), right.reasonDigest());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String correlationId(String supplied) {
        return blank(supplied) ? UUID.randomUUID().toString() : supplied;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private record ResolvedAuthentication(
            TypedManagementCredentialSource source,
            AuthorizationReasonCode reasonCode,
            ManagementAuthenticationContext authenticationContext,
            AuthorizationManagementTokenEntity token,
            String tokenHash,
            String tokenReference,
            ManagementTokenPurpose tokenPurpose
    ) {
        static ResolvedAuthentication denied(TypedManagementCredentialSource source, AuthorizationReasonCode reasonCode) {
            return new ResolvedAuthentication(source, reasonCode, null, null, null, null, null);
        }

        static ResolvedAuthentication allowed(TypedManagementCredentialSource source,
                                              ManagementAuthenticationContext context) {
            return new ResolvedAuthentication(source, null, context, null, null, null, context.tokenPurpose());
        }

        boolean allowed() {
            return reasonCode == null && authenticationContext != null;
        }

        ResolvedAuthentication withToken(AuthorizationManagementTokenEntity token,
                                         String tokenHash,
                                         String tokenReference,
                                         ManagementTokenPurpose tokenPurpose) {
            return new ResolvedAuthentication(source, reasonCode, authenticationContext, token,
                    tokenHash, tokenReference, tokenPurpose);
        }

        ResolvedAuthentication withSource(TypedManagementCredentialSource source) {
            return new ResolvedAuthentication(source, reasonCode, authenticationContext, token,
                    tokenHash, tokenReference, tokenPurpose);
        }
    }

    private record ContextRevalidation(AuthorizationReasonCode reasonCode,
                                       ManagementAuthenticationContext authenticationContext) {
        static ContextRevalidation denied(AuthorizationReasonCode reasonCode) {
            return new ContextRevalidation(reasonCode, null);
        }

        static ContextRevalidation allowed(ManagementAuthenticationContext context) {
            return new ContextRevalidation(null, context);
        }

        boolean allowed() {
            return reasonCode == null && authenticationContext != null;
        }
    }
}
