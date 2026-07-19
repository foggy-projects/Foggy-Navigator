package com.foggy.navigator.common.authorization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Internal, redacted input for a canonical decision audit record.
 *
 * <p>The type deliberately has no fields for a credential, token, account,
 * request body, query value, or upstream user token. Values that could
 * identify a principal, credential, target, request, or impact are accepted
 * only as structurally valid SHA-256 fingerprints.</p>
 *
 * <p>This type deliberately has no public constructor or factory. Only the
 * audit store can derive it from a canonical shadow result, the frozen route
 * catalog, and server-owned deployment identity. That keeps callers from
 * fabricating a route, diff, or deployment provenance before an append.</p>
 */
public final class AuthorizationDecisionAuditDraft {

    private final String decisionId;
    private final String schemaVersion;
    private final String policyVersion;
    private final String actionCatalogVersion;
    private final String serverBuild;
    private final String correlationId;
    private final String evaluationMode;
    private final String principalType;
    private final String principalFingerprint;
    private final String credentialLane;
    private final String credentialFingerprint;
    private final String actionId;
    private final String targetType;
    private final String targetFingerprint;
    private final String routeId;
    private final String requestDigest;
    private final String impactDigest;
    private final String decision;
    private final String reasonCode;
    private final String legacyDecision;
    private final String legacyReasonCode;
    private final String diffCode;
    private final Instant evaluatedAt;

    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:/{}-]+");
    private static final Pattern SAFE_MANIFEST_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:/{}\\[\\]|-]+");
    private static final Pattern SAFE_ENUM = Pattern.compile("[A-Z0-9_]+$");
    private static final String UNREGISTERED_ROUTE_ID = "UNREGISTERED_ROUTE";
    private static final String UNREGISTERED_ACTION_ID = "unregistered.action";

    private AuthorizationDecisionAuditDraft(
            String decisionId,
            String schemaVersion,
            String policyVersion,
            String actionCatalogVersion,
            String serverBuild,
            String correlationId,
            String evaluationMode,
            String principalType,
            String principalFingerprint,
            String credentialLane,
            String credentialFingerprint,
            String actionId,
            String targetType,
            String targetFingerprint,
            String routeId,
            String requestDigest,
            String impactDigest,
            String decision,
            String reasonCode,
            String legacyDecision,
            String legacyReasonCode,
            String diffCode,
            Instant evaluatedAt) {
        this.decisionId = decisionId;
        this.schemaVersion = schemaVersion;
        this.policyVersion = policyVersion;
        this.actionCatalogVersion = actionCatalogVersion;
        this.serverBuild = serverBuild;
        this.correlationId = correlationId;
        this.evaluationMode = evaluationMode;
        this.principalType = principalType;
        this.principalFingerprint = principalFingerprint;
        this.credentialLane = credentialLane;
        this.credentialFingerprint = credentialFingerprint;
        this.actionId = actionId;
        this.targetType = targetType;
        this.targetFingerprint = targetFingerprint;
        this.routeId = routeId;
        this.requestDigest = requestDigest;
        this.impactDigest = impactDigest;
        this.decision = decision;
        this.reasonCode = reasonCode;
        this.legacyDecision = legacyDecision;
        this.legacyReasonCode = legacyReasonCode;
        this.diffCode = diffCode;
        this.evaluatedAt = evaluatedAt;
    }

    /**
     * Creates an audit-safe draft only from a P1A shadow result. A route is
     * copied only from the source-controlled manifest; an unregistered
     * request is represented by stable constants so request path values never
     * enter the durable audit record.
     */
    static AuthorizationDecisionAuditDraft fromShadow(
            AuthorizationContextV1 context,
            PolicyDecisionV1 canonicalDecision,
            LegacyEnforcementOutcome legacyOutcome,
            int httpStatus,
            AuthorizationRouteManifestEntry registeredRoute) {
        Objects.requireNonNull(canonicalDecision, "canonicalDecision must not be null");

        String routeId = registeredRoute == null ? UNREGISTERED_ROUTE_ID : registeredRoute.routeId();
        String actionId = registeredRoute == null ? UNREGISTERED_ACTION_ID : registeredRoute.canonicalAction();
        String targetType = registeredRoute == null ? UNREGISTERED_ROUTE_ID : registeredRoute.targetResolver();
        String routeDigestInput = registeredRoute == null
                ? "unregistered:" + safeHttpMethod(context)
                : registeredRoute.httpMethod() + ":" + registeredRoute.path();
        LegacyEnforcementOutcome effectiveLegacyOutcome = legacyOutcome == null
                ? LegacyEnforcementOutcome.UNKNOWN : legacyOutcome;

        return new AuthorizationDecisionAuditDraft(
                canonicalDecision.decisionId(),
                canonicalDecision.schemaVersion(),
                canonicalDecision.policyVersion(),
                canonicalDecision.actionCatalogVersion(),
                canonicalDecision.serverBuild(),
                canonicalDecision.correlationId(),
                canonicalDecision.evaluationMode().name(),
                principalType(context),
                fingerprint(principalReference(context)),
                credentialLane(context),
                fingerprint(credentialReference(context)),
                actionId,
                targetType,
                fingerprint(targetType),
                routeId,
                fingerprint(routeDigestInput),
                null,
                canonicalDecision.decision().name(),
                canonicalDecision.reasonCode().name(),
                effectiveLegacyOutcome.name(),
                safeHttpStatusReason(httpStatus),
                diffCode(AuthorizationDecisionDiff.compare(effectiveLegacyOutcome, canonicalDecision)),
                canonicalDecision.evaluatedAt() == null ? Instant.now() : canonicalDecision.evaluatedAt()
        );
    }

    /** Returns a stable SHA-256 fingerprint without retaining the input. */
    static String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Validates that this internally derived input contains only canonical metadata and digests. */
    public void validate() {
        requireExact("schemaVersion", schemaVersion, AuthorizationSchemaV1.SCHEMA_VERSION);
        requireExact("policyVersion", policyVersion, AuthorizationSchemaV1.POLICY_VERSION);
        requireExact("actionCatalogVersion", actionCatalogVersion, AuthorizationSchemaV1.ACTION_CATALOG_VERSION);
        requireIdentifier("decisionId", decisionId, 64);
        requireIdentifier("serverBuild", serverBuild, 128);
        requireIdentifier("correlationId", correlationId, 128);
        requireEnum("evaluationMode", evaluationMode, 32);
        requireEnum("principalType", principalType, 64);
        requireEnum("credentialLane", credentialLane, 64);
        requireIdentifier("actionId", actionId, 160);
        requireManifestIdentifier("targetType", targetType, 160);
        requireIdentifier("routeId", routeId, 192);
        requireEnum("decision", decision, 16);
        requireEnum("reasonCode", reasonCode, 160);
        requireEnum("legacyDecision", legacyDecision, 16);
        requireEnum("legacyReasonCode", legacyReasonCode, 160);
        requireEnum("diffCode", diffCode, 96);
        requireDigest("principalFingerprint", principalFingerprint);
        requireDigest("credentialFingerprint", credentialFingerprint);
        requireDigest("targetFingerprint", targetFingerprint);
        requireDigest("requestDigest", requestDigest);
        requireDigest("impactDigest", impactDigest);
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("evaluatedAt is required for authorization decision audit append");
        }
    }

    private static String safeHttpMethod(AuthorizationContextV1 context) {
        if (context == null || context.route() == null || context.route().httpMethod() == null) {
            return "UNKNOWN";
        }
        String method = context.route().httpMethod().trim().toUpperCase();
        return SAFE_ENUM.matcher(method).matches() && method.length() <= 32 ? method : "UNKNOWN";
    }

    private static String principalType(AuthorizationContextV1 context) {
        if (context == null || context.principal() == null || context.principal().principalType() == null) {
            return AuthorizationPrincipalType.UNKNOWN.name();
        }
        return context.principal().principalType().name();
    }

    private static String credentialLane(AuthorizationContextV1 context) {
        if (context == null || context.credential() == null || context.credential().credentialLane() == null) {
            return AuthorizationCredentialLane.UNKNOWN.name();
        }
        return context.credential().credentialLane().name();
    }

    private static String principalReference(AuthorizationContextV1 context) {
        return context == null || context.principal() == null ? null : context.principal().principalReference();
    }

    private static String credentialReference(AuthorizationContextV1 context) {
        return context == null || context.credential() == null ? null : context.credential().credentialReference();
    }

    private static String safeHttpStatusReason(int status) {
        return status >= 100 && status <= 599 ? "HTTP_STATUS_" + status : "HTTP_STATUS_UNKNOWN";
    }

    private static String diffCode(AuthorizationDecisionDiff diff) {
        if (diff == null || diff.legacyOutcome() == LegacyEnforcementOutcome.UNKNOWN
                || diff.canonicalOutcome() == AuthorizationDecisionOutcome.UNKNOWN) {
            return "UNKNOWN";
        }
        if (!diff.differs()) {
            return "MATCH";
        }
        return "LEGACY_" + diff.legacyOutcome().name() + "_CANONICAL_" + diff.canonicalOutcome().name();
    }

    private static void requireExact(String name, String value, String expected) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(name + " must equal " + expected + " for authorization decision audit append");
        }
    }

    private static void requireIdentifier(String name, String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a bounded canonical identifier for authorization decision audit append");
        }
    }

    private static void requireEnum(String name, String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !SAFE_ENUM.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a bounded canonical enum for authorization decision audit append");
        }
    }

    private static void requireManifestIdentifier(String name, String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !SAFE_MANIFEST_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name
                    + " must be a bounded source-controlled manifest identifier for authorization decision audit append");
        }
    }

    private static void requireDigest(String name, String value) {
        if (value != null && !SHA_256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a SHA-256 fingerprint for authorization decision audit append");
        }
    }

    public String decisionId() {
        return decisionId;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public String policyVersion() {
        return policyVersion;
    }

    public String actionCatalogVersion() {
        return actionCatalogVersion;
    }

    public String serverBuild() {
        return serverBuild;
    }

    public String correlationId() {
        return correlationId;
    }

    public String evaluationMode() {
        return evaluationMode;
    }

    public String principalType() {
        return principalType;
    }

    public String principalFingerprint() {
        return principalFingerprint;
    }

    public String credentialLane() {
        return credentialLane;
    }

    public String credentialFingerprint() {
        return credentialFingerprint;
    }

    public String actionId() {
        return actionId;
    }

    public String targetType() {
        return targetType;
    }

    public String targetFingerprint() {
        return targetFingerprint;
    }

    public String routeId() {
        return routeId;
    }

    public String requestDigest() {
        return requestDigest;
    }

    public String impactDigest() {
        return impactDigest;
    }

    public String decision() {
        return decision;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String legacyDecision() {
        return legacyDecision;
    }

    public String legacyReasonCode() {
        return legacyReasonCode;
    }

    public String diffCode() {
        return diffCode;
    }

    public Instant evaluatedAt() {
        return evaluatedAt;
    }
}
