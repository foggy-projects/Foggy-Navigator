package com.foggy.navigator.common.authorization.preseed;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hermetic validator for synthetic or securely supplied pre-seed inventory.
 * It is intentionally not an authorization engine, seed planner, or approval
 * service; callers must obtain real mapping and four-eyes evidence outside of
 * this repository before any later P1B-B work can begin.
 */
public final class PreseedInventoryValidator {

    private static final Pattern OPAQUE_REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{2,127}");
    private static final Pattern OPAQUE_ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,63}");
    private static final Pattern FINGERPRINT_EDGE = Pattern.compile("[a-f0-9]{8,16}");
    private static final Pattern CHECKSUM = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern JWT_LIKE = Pattern.compile("(?:^|[^A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{10,}");
    private static final Pattern ENV_ASSIGNMENT = Pattern.compile(
            "(?i).*\\b[A-Z_][A-Z0-9_]*(?:TOKEN|SECRET|KEY|PASSWORD|PROFILE|ENV)[A-Z0-9_]*\\s*=.*");

    private static final Set<String> FORBIDDEN_FIELD_NAMES = Set.of(
            "secret",
            "credentialmaterial",
            "credentialsecret",
            "credentialvalue",
            "verifier",
            "rawverifier",
            "hash",
            "rawhash",
            "token",
            "accesstoken",
            "upstreamusertoken",
            "key",
            "apikey",
            "password",
            "profilecontent",
            "environmentcontent",
            "envcontent",
            "requestbody",
            "fullrequestbody",
            "body");

    private final PreseedInventoryCodec codec;
    private final Clock clock;

    public PreseedInventoryValidator() {
        this(new PreseedInventoryCodec(), Clock.systemUTC());
    }

    PreseedInventoryValidator(PreseedInventoryCodec codec) {
        this(codec, Clock.systemUTC());
    }

    /**
     * Package-visible deterministic clock seam for offline contract tests.
     * Production callers use the public constructor and UTC wall-clock expiry
     * semantics; this does not make a validation result an approval.
     */
    PreseedInventoryValidator(Clock clock) {
        this(new PreseedInventoryCodec(), clock);
    }

    PreseedInventoryValidator(PreseedInventoryCodec codec, Clock clock) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Validates only the supplied document. This method does not read a file,
     * environment variable, profile, database, network resource, or secret
     * store.
     */
    public PreseedInventoryValidationResult validate(String document) {
        PreseedInventoryCodec.DecodedDocument decoded = codec.decode(document);
        if (!decoded.successful()) {
            return PreseedInventoryValidationResult.invalid(decoded.failureReason(), 0, null);
        }
        return validate(decoded.document());
    }

    private PreseedInventoryValidationResult validate(JsonNode envelope) {
        if (!envelope.isObject()) {
            return PreseedInventoryValidationResult.invalid(
                    PreseedInventoryReasonCode.PRESEED_DOCUMENT_MALFORMED, 0, null);
        }

        // This must happen before shape, checksum, and semantic classification.
        if (containsSecretLikeData(envelope)) {
            return PreseedInventoryValidationResult.invalid(
                    PreseedInventoryReasonCode.PRESEED_SECRET_LIKE_INPUT, 0, null);
        }
        if (!hasOnlyFields(envelope, PreseedInventorySchemaV1.ENVELOPE_FIELDS)) {
            return PreseedInventoryValidationResult.invalid(
                    PreseedInventoryReasonCode.PRESEED_UNSUPPORTED_FIELD, 0, null);
        }
        if (!textEquals(envelope, "schemaVersion", PreseedInventorySchemaV1.SCHEMA_VERSION)) {
            return PreseedInventoryValidationResult.invalid(
                    PreseedInventoryReasonCode.PRESEED_SCHEMA_UNSUPPORTED, 0, null);
        }
        if (!textEquals(envelope, "mode", PreseedInventorySchemaV1.MODE_OFFLINE_VALIDATE_ONLY)) {
            return PreseedInventoryValidationResult.invalid(
                    PreseedInventoryReasonCode.PRESEED_MODE_UNSUPPORTED, 0, null);
        }

        JsonNode deployment = envelope.path("deployment");
        if (!deployment.isObject() || !hasOnlyFields(deployment, PreseedInventorySchemaV1.DEPLOYMENT_FIELDS)
                || !safeReference(text(deployment, "navigatorInstanceId"))
                || !safeReference(text(deployment, "environmentProfile"))) {
            return PreseedInventoryValidationResult.invalid(
                    PreseedInventoryReasonCode.PRESEED_DEPLOYMENT_MISSING, 0, null);
        }

        JsonNode records = envelope.path("records");
        if (!records.isArray() || records.isEmpty()) {
            return PreseedInventoryValidationResult.invalid(
                    PreseedInventoryReasonCode.PRESEED_RECORDS_MISSING, 0, null);
        }
        int recordCount = records.size();
        String canonicalChecksum = PreseedInventoryCanonicalizer.checksum(envelope);
        if (!envelope.path("checksum").isTextual()
                || !CHECKSUM.matcher(envelope.path("checksum").textValue()).matches()
                || !MessageDigest.isEqual(envelope.path("checksum").textValue().getBytes(StandardCharsets.US_ASCII),
                canonicalChecksum.getBytes(StandardCharsets.US_ASCII))) {
            return PreseedInventoryValidationResult.invalid(
                    PreseedInventoryReasonCode.PRESEED_CHECKSUM_MISMATCH, recordCount, canonicalChecksum);
        }

        Map<String, TenantAuthorityBinding> tenantAuthorities = new HashMap<>();
        Map<String, ClientAppBinding> clientApps = new HashMap<>();
        Set<String> recordAliases = new HashSet<>();
        for (JsonNode record : records) {
            PreseedInventoryValidationResult recordResult = validateRecord(
                    record, recordCount, canonicalChecksum, tenantAuthorities, clientApps, recordAliases);
            if (recordResult != null) {
                return recordResult;
            }
        }
        return PreseedInventoryValidationResult.valid(recordCount, canonicalChecksum);
    }

    private PreseedInventoryValidationResult validateRecord(JsonNode record,
                                                            int recordCount,
                                                            String checksum,
                                                            Map<String, TenantAuthorityBinding> tenantAuthorities,
                                                            Map<String, ClientAppBinding> clientApps,
                                                            Set<String> recordAliases) {
        if (!record.isObject() || !allRecordFieldTypesSupported(record)) {
            return PreseedInventoryValidationResult.invalid(
                    PreseedInventoryReasonCode.PRESEED_RECORD_INVALID, recordCount, checksum);
        }
        if (!hasOnlyFields(record, PreseedInventorySchemaV1.RECORD_FIELDS)) {
            return PreseedInventoryValidationResult.invalid(
                    PreseedInventoryReasonCode.PRESEED_UNSUPPORTED_FIELD, recordCount, checksum);
        }

        String alias = text(record, "recordAlias");
        if (!safeAlias(alias) || !recordAliases.add(alias)) {
            return PreseedInventoryValidationResult.invalid(
                    PreseedInventoryReasonCode.PRESEED_RECORD_INVALID, recordCount, checksum);
        }
        if (!safeReference(text(record, "upstreamSystemReference"))
                || !safeReference(text(record, "namespaceReference"))
                || !safeReference(text(record, "ownerReference"))
                || !safeReference(text(record, "approvalReference"))
                || !PreseedInventorySchemaV1.SOURCE_KINDS.contains(text(record, "sourceKind"))
                || !PreseedInventorySchemaV1.PRINCIPAL_TYPES.contains(text(record, "proposedPrincipalType"))
                || !PreseedInventorySchemaV1.CREDENTIAL_LANES.contains(text(record, "proposedCredentialLane"))
                || !PreseedInventorySchemaV1.DISPOSITIONS.contains(text(record, "proposedDisposition"))) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_AUTHORITY_FACTS_MISSING,
                    alias, recordCount, checksum);
        }
        if (!record.path("ownerConflict").isBoolean() || record.path("ownerConflict").booleanValue()) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_OWNER_CONFLICT, alias, recordCount, checksum);
        }
        if (!record.path("clientAppConflict").isBoolean() || record.path("clientAppConflict").booleanValue()) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_CLIENT_APP_CONFLICT,
                    alias, recordCount, checksum);
        }
        if (!record.path("authorityFactsComplete").isBoolean() || !record.path("authorityFactsComplete").booleanValue()) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_AUTHORITY_FACTS_MISSING,
                    alias, recordCount, checksum);
        }

        PreseedInventoryValidationResult mappingResult = mappingResult(record, alias, recordCount, checksum);
        if (mappingResult != null) {
            return mappingResult;
        }
        PreseedInventoryValidationResult credentialResult = credentialResult(record, alias, recordCount, checksum);
        if (credentialResult != null) {
            return credentialResult;
        }

        String sourceKind = text(record, "sourceKind");
        if (isLegacy(sourceKind)) {
            return legacyResult(record, alias, recordCount, checksum);
        }
        if ("S1_INSTANCE_ROOT_DECLARATION".equals(sourceKind)) {
            PreseedInventoryValidationResult s1Result = validateS1Scope(record, alias, recordCount, checksum);
            if (s1Result != null) {
                return s1Result;
            }
        } else if ("S2_SAAS_PLATFORM_DECLARATION".equals(sourceKind)) {
            PreseedInventoryValidationResult s2Result = validateS2Scope(
                    record, alias, recordCount, checksum, tenantAuthorities, clientApps);
            if (s2Result != null) {
                return s2Result;
            }
        } else {
            return PreseedInventoryValidationResult.invalid(
                    PreseedInventoryReasonCode.PRESEED_RECORD_INVALID, recordCount, checksum);
        }

        return dispositionResult(record, alias, recordCount, checksum);
    }

    private PreseedInventoryValidationResult mappingResult(JsonNode record,
                                                           String alias,
                                                           int recordCount,
                                                           String checksum) {
        String sourceMappingState = text(record, "sourceMappingState");
        if ("MISSING".equals(sourceMappingState)) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_SOURCE_MAPPING_MISSING,
                    alias, recordCount, checksum);
        }
        if ("AMBIGUOUS".equals(sourceMappingState)) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_SOURCE_MAPPING_AMBIGUOUS,
                    alias, recordCount, checksum);
        }
        if ("CONFLICT".equals(sourceMappingState)) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_SOURCE_MAPPING_CONFLICT,
                    alias, recordCount, checksum);
        }
        if (!"VERIFIED".equals(sourceMappingState)) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_SOURCE_MAPPING_MISSING,
                    alias, recordCount, checksum);
        }
        return null;
    }

    private PreseedInventoryValidationResult credentialResult(JsonNode record,
                                                              String alias,
                                                              int recordCount,
                                                              String checksum) {
        if (!safeFingerprint(text(record, "credentialFingerprintPrefix"))
                || !safeFingerprint(text(record, "credentialFingerprintSuffix"))) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_FINGERPRINT_INVALID,
                    alias, recordCount, checksum);
        }
        if (!record.path("revoked").isBoolean()) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_CREDENTIAL_STATUS_INVALID,
                    alias, recordCount, checksum);
        }
        if (record.path("revoked").booleanValue() || "REVOKED".equals(text(record, "credentialStatus"))) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_CREDENTIAL_REVOKED,
                    alias, recordCount, checksum);
        }
        if (!"ACTIVE".equals(text(record, "credentialStatus"))) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_CREDENTIAL_STATUS_INVALID,
                    alias, recordCount, checksum);
        }
        String expiry = text(record, "expiresAt");
        if (expiry == null || expiry.isBlank()) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_CREDENTIAL_EXPIRY_REQUIRED,
                    alias, recordCount, checksum);
        }
        try {
            if (!Instant.parse(expiry).isAfter(clock.instant())) {
                return quarantine(PreseedInventoryReasonCode.PRESEED_CREDENTIAL_EXPIRED,
                        alias, recordCount, checksum);
            }
        } catch (DateTimeParseException exception) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_CREDENTIAL_EXPIRY_REQUIRED,
                    alias, recordCount, checksum);
        }
        return null;
    }

    private PreseedInventoryValidationResult validateS1Scope(JsonNode record,
                                                             String alias,
                                                             int recordCount,
                                                             String checksum) {
        if (!"INSTANCE_ROOT".equals(text(record, "proposedPrincipalType"))
                || !("INSTANCE_ROOT_CONTROL".equals(text(record, "proposedCredentialLane"))
                || "INSTANCE_ROOT_SECURITY".equals(text(record, "proposedCredentialLane")))
                || present(record, "tenantReference")
                || present(record, "clientAppReference")
                || present(record, "tenantAuthorityReference")
                || present(record, "tenantAuthorityState")) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_AUTHORITY_FACTS_MISSING,
                    alias, recordCount, checksum);
        }
        return null;
    }

    private PreseedInventoryValidationResult validateS2Scope(JsonNode record,
                                                             String alias,
                                                             int recordCount,
                                                             String checksum,
                                                             Map<String, TenantAuthorityBinding> tenantAuthorities,
                                                             Map<String, ClientAppBinding> clientApps) {
        if (!"SAAS_PLATFORM".equals(text(record, "proposedPrincipalType"))
                || !("SAAS_PROVISIONING".equals(text(record, "proposedCredentialLane"))
                || "SAAS_SECURITY_ADMIN".equals(text(record, "proposedCredentialLane")))
                || !safeReference(text(record, "tenantReference"))
                || !safeReference(text(record, "clientAppReference"))
                || !safeReference(text(record, "tenantAuthorityReference"))) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_AUTHORITY_FACTS_MISSING,
                    alias, recordCount, checksum);
        }
        String tenantAuthorityState = text(record, "tenantAuthorityState");
        if ("MISSING".equals(tenantAuthorityState)) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_AUTHORITY_FACTS_MISSING,
                    alias, recordCount, checksum);
        }
        if ("AMBIGUOUS".equals(tenantAuthorityState)) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_SOURCE_MAPPING_AMBIGUOUS,
                    alias, recordCount, checksum);
        }
        if ("CONFLICT".equals(tenantAuthorityState)) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_TENANT_AUTHORITY_CONFLICT,
                    alias, recordCount, checksum);
        }
        if (!"VERIFIED".equals(tenantAuthorityState)) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_AUTHORITY_FACTS_MISSING,
                    alias, recordCount, checksum);
        }

        String tenantReference = text(record, "tenantReference");
        TenantAuthorityBinding current = new TenantAuthorityBinding(
                text(record, "tenantAuthorityReference"), text(record, "ownerReference"));
        TenantAuthorityBinding previous = tenantAuthorities.putIfAbsent(tenantReference, current);
        if (previous != null && previous.equals(current)) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_TENANT_AUTHORITY_DUPLICATE,
                    alias, recordCount, checksum);
        }
        if (previous != null) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_TENANT_AUTHORITY_CONFLICT,
                    alias, recordCount, checksum);
        }

        String clientAppReference = text(record, "clientAppReference");
        ClientAppBinding clientApp = new ClientAppBinding(tenantReference, text(record, "ownerReference"));
        ClientAppBinding previousClientApp = clientApps.putIfAbsent(clientAppReference, clientApp);
        if (previousClientApp != null) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_CLIENT_APP_CONFLICT,
                    alias, recordCount, checksum);
        }
        return null;
    }

    private PreseedInventoryValidationResult legacyResult(JsonNode record,
                                                          String alias,
                                                          int recordCount,
                                                          String checksum) {
        String principalType = text(record, "proposedPrincipalType");
        String credentialLane = text(record, "proposedCredentialLane");
        if (!"UNSPECIFIED".equals(principalType) || !"UNSPECIFIED".equals(credentialLane)) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_LEGACY_PROMOTION_PROHIBITED,
                    alias, recordCount, checksum);
        }
        return quarantine(PreseedInventoryReasonCode.PRESEED_LEGACY_REQUIRES_APPROVAL,
                alias, recordCount, checksum);
    }

    private PreseedInventoryValidationResult dispositionResult(JsonNode record,
                                                               String alias,
                                                               int recordCount,
                                                               String checksum) {
        String disposition = text(record, "proposedDisposition");
        if ("CANDIDATE".equals(disposition)) {
            return null;
        }
        if ("REQUIRES_APPROVAL".equals(disposition)) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_DISPOSITION_REQUIRES_APPROVAL,
                    alias, recordCount, checksum);
        }
        if ("QUARANTINED".equals(disposition) && safeReference(text(record, "quarantineReason"))) {
            return quarantine(PreseedInventoryReasonCode.PRESEED_RECORD_DECLARED_QUARANTINE,
                    alias, recordCount, checksum);
        }
        return quarantine(PreseedInventoryReasonCode.PRESEED_AUTHORITY_FACTS_MISSING,
                alias, recordCount, checksum);
    }

    private static boolean allRecordFieldTypesSupported(JsonNode record) {
        Set<String> booleanFields = Set.of("ownerConflict", "clientAppConflict", "authorityFactsComplete", "revoked");
        Iterator<Map.Entry<String, JsonNode>> fields = record.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (booleanFields.contains(field.getKey())) {
                if (!value.isBoolean()) {
                    return false;
                }
            } else if (!value.isTextual() && !value.isNull()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasOnlyFields(JsonNode object, Set<String> allowedFields) {
        Iterator<String> fieldNames = object.fieldNames();
        while (fieldNames.hasNext()) {
            if (!allowedFields.contains(fieldNames.next())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsSecretLikeData(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (forbiddenFieldName(field.getKey()) || containsSecretLikeData(field.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsSecretLikeData(item)) {
                    return true;
                }
            }
            return false;
        }
        return node.isTextual() && secretLikeValue(node.textValue());
    }

    private static boolean forbiddenFieldName(String fieldName) {
        String normalized = fieldName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return FORBIDDEN_FIELD_NAMES.contains(normalized)
                || normalized.contains("credentialmaterial")
                || normalized.contains("credentialsecret")
                || normalized.contains("rawverifier")
                || normalized.contains("rawhash")
                || normalized.contains("requestbody");
    }

    private static boolean secretLikeValue(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("bearer ")
                || normalized.contains("api_key")
                || normalized.contains("api-key")
                || normalized.contains("apikey")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("private key")
                || normalized.contains("raw verifier")
                || normalized.contains("raw hash")
                || normalized.contains("access_token")
                || normalized.contains("upstream user token")
                || normalized.contains("authorization:")
                || normalized.contains("cookie:")
                || JWT_LIKE.matcher(value).find()
                || ENV_ASSIGNMENT.matcher(value).matches();
    }

    private static boolean textEquals(JsonNode object, String field, String expected) {
        return object.path(field).isTextual() && expected.equals(object.path(field).textValue());
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean present(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && !value.isNull() && !(value.isTextual() && value.textValue().isBlank());
    }

    private static boolean safeReference(String value) {
        return value != null && OPAQUE_REFERENCE.matcher(value).matches() && !secretLikeValue(value);
    }

    private static boolean safeAlias(String value) {
        return value != null && OPAQUE_ALIAS.matcher(value).matches() && !secretLikeValue(value);
    }

    private static boolean safeFingerprint(String value) {
        return value != null && FINGERPRINT_EDGE.matcher(value).matches();
    }

    private static boolean isLegacy(String sourceKind) {
        return sourceKind != null && sourceKind.startsWith("LEGACY_");
    }

    private static PreseedInventoryValidationResult quarantine(PreseedInventoryReasonCode reasonCode,
                                                                String alias,
                                                                int recordCount,
                                                                String checksum) {
        return PreseedInventoryValidationResult.quarantined(reasonCode, alias, recordCount, checksum);
    }

    private record TenantAuthorityBinding(String authorityReference, String ownerReference) {
    }

    private record ClientAppBinding(String tenantReference, String ownerReference) {
    }
}
