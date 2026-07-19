package com.foggy.navigator.common.authorization.preseed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreseedInventoryValidatorTest {

    private static final String FIXTURE_ROOT = "/authorization/preseed-inventory/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant FIXED_NOW = Instant.parse("2026-07-19T00:00:00Z");

    private final PreseedInventoryValidator validator = new PreseedInventoryValidator(
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    @Test
    void acceptsTheSyntheticS1AndS2CandidateFixtureWithItsFrozenChecksum() throws Exception {
        ObjectNode fixture = fixture("valid-s1-s2-candidates.json");

        PreseedInventoryValidationResult result = validate(fixture);

        assertEquals(PreseedInventoryClassification.VALID, result.classification());
        assertEquals(PreseedInventoryReasonCode.PRESEED_VALID, result.reasonCode());
        assertNull(result.recordAlias());
        assertEquals(2, result.recordCount());
        assertEquals("428d140a6f31f353815de24bfdbfaa2b2f6ea5a0083e05be0e917f3d18519a07",
                result.checksum());
        assertEquals(result.checksum(), fixture.path("checksum").textValue());
    }

    @Test
    void rejectsUnsupportedEnvelopeVersionModeDeploymentRecordsAndChecksumWithStableReasons() throws Exception {
        ObjectNode unsupportedSchema = fixture("valid-s1-s2-candidates.json");
        unsupportedSchema.put("schemaVersion", "unsupported");
        recalculateChecksum(unsupportedSchema);
        assertInvalid(validate(unsupportedSchema), PreseedInventoryReasonCode.PRESEED_SCHEMA_UNSUPPORTED);

        ObjectNode unsupportedMode = fixture("valid-s1-s2-candidates.json");
        unsupportedMode.put("mode", "SEED_NOW");
        recalculateChecksum(unsupportedMode);
        assertInvalid(validate(unsupportedMode), PreseedInventoryReasonCode.PRESEED_MODE_UNSUPPORTED);

        ObjectNode missingDeployment = fixture("valid-s1-s2-candidates.json");
        missingDeployment.remove("deployment");
        recalculateChecksum(missingDeployment);
        assertInvalid(validate(missingDeployment), PreseedInventoryReasonCode.PRESEED_DEPLOYMENT_MISSING);

        ObjectNode missingRecords = fixture("valid-s1-s2-candidates.json");
        missingRecords.remove("records");
        recalculateChecksum(missingRecords);
        assertInvalid(validate(missingRecords), PreseedInventoryReasonCode.PRESEED_RECORDS_MISSING);

        assertInvalid(validate(fixture("invalid-checksum.json")),
                PreseedInventoryReasonCode.PRESEED_CHECKSUM_MISMATCH);
    }

    @Test
    void rejectsForbiddenFieldsAndValuesBeforeClassificationAndNeverEchoesThem() throws Exception {
        ObjectNode fieldSecret = fixture("quarantined-owner-conflict.json");
        String rawFieldValue = "raw-secret-material-do-not-echo";
        record(fieldSecret, 0).put("credentialMaterial", rawFieldValue);
        recalculateChecksum(fieldSecret);

        PreseedInventoryValidationResult fieldResult = validate(fieldSecret);
        assertInvalid(fieldResult, PreseedInventoryReasonCode.PRESEED_SECRET_LIKE_INPUT);
        assertNull(fieldResult.recordAlias());
        assertFalse(fieldResult.toString().contains(rawFieldValue));

        ObjectNode nestedSecret = fixture("quarantined-owner-conflict.json");
        String nestedRawValue = "nested-secret-material-do-not-echo";
        record(nestedSecret, 0).putObject("unexpectedWrapper").put("accessToken", nestedRawValue);
        recalculateChecksum(nestedSecret);

        PreseedInventoryValidationResult nestedResult = validate(nestedSecret);
        assertInvalid(nestedResult, PreseedInventoryReasonCode.PRESEED_SECRET_LIKE_INPUT);
        assertNull(nestedResult.recordAlias());
        assertFalse(nestedResult.toString().contains(nestedRawValue));

        ObjectNode valueSecret = fixture("quarantined-owner-conflict.json");
        String rawValue = "Bearer fixture-not-a-real-credential";
        record(valueSecret, 0).put("ownerReference", rawValue);
        recalculateChecksum(valueSecret);

        PreseedInventoryValidationResult valueResult = validate(valueSecret);
        assertInvalid(valueResult, PreseedInventoryReasonCode.PRESEED_SECRET_LIKE_INPUT);
        assertNull(valueResult.recordAlias());
        assertFalse(valueResult.toString().contains(rawValue));

        ObjectNode environmentContent = fixture("quarantined-owner-conflict.json");
        String rawEnvironmentContent = "NAVIGATOR_RUNTIME_TOKEN=not-a-real-token";
        record(environmentContent, 0).put("approvalReference", rawEnvironmentContent);
        recalculateChecksum(environmentContent);

        PreseedInventoryValidationResult environmentResult = validate(environmentContent);
        assertInvalid(environmentResult, PreseedInventoryReasonCode.PRESEED_SECRET_LIKE_INPUT);
        assertFalse(environmentResult.toString().contains(rawEnvironmentContent));

        String duplicateAllowedRawValue = "Bearer duplicate-not-a-real-credential";
        String duplicateAllowedField = MAPPER.writeValueAsString(fixture("valid-s1-s2-candidates.json"))
                .replace("\"ownerReference\":\"synthetic-s1-owner\"",
                        "\"ownerReference\":\"" + duplicateAllowedRawValue
                                + "\",\"ownerReference\":\"synthetic-s1-owner\"");
        PreseedInventoryValidationResult duplicateAllowedResult = validator.validate(duplicateAllowedField);
        assertInvalid(duplicateAllowedResult, PreseedInventoryReasonCode.PRESEED_DOCUMENT_MALFORMED);
        assertFalse(duplicateAllowedResult.toString().contains(duplicateAllowedRawValue));

        String duplicateForbiddenRawValue = "duplicate-forbidden-not-a-real-token";
        String duplicateForbiddenField = MAPPER.writeValueAsString(fixture("valid-s1-s2-candidates.json"))
                .replace("\"approvalReference\":\"synthetic-approval-s1-001\"",
                        "\"approvalReference\":\"synthetic-approval-s1-001\",\"token\":\""
                                + duplicateForbiddenRawValue + "\",\"token\":\"synthetic-token-repeat\"");
        PreseedInventoryValidationResult duplicateForbiddenResult = validator.validate(duplicateForbiddenField);
        assertInvalid(duplicateForbiddenResult, PreseedInventoryReasonCode.PRESEED_DOCUMENT_MALFORMED);
        assertFalse(duplicateForbiddenResult.toString().contains(duplicateForbiddenRawValue));
    }

    @Test
    void rejectsUnapprovedRecordFieldsWithoutTreatingThemAsAuthorityFacts() throws Exception {
        ObjectNode document = fixture("valid-s1-s2-candidates.json");
        record(document, 0).put("unexpectedDiagnostic", "synthetic-value");
        recalculateChecksum(document);

        assertInvalid(validate(document), PreseedInventoryReasonCode.PRESEED_UNSUPPORTED_FIELD);
    }

    @Test
    void quarantinesConflictMappingAndCredentialFactFailures() throws Exception {
        ObjectNode ownerConflict = fixture("quarantined-owner-conflict.json");
        assertQuarantined(validate(ownerConflict), PreseedInventoryReasonCode.PRESEED_OWNER_CONFLICT,
                "synthetic-owner-conflict");

        ObjectNode missingMapping = fixture("valid-s1-s2-candidates.json");
        record(missingMapping, 0).put("sourceMappingState", "MISSING");
        recalculateChecksum(missingMapping);
        assertQuarantined(validate(missingMapping), PreseedInventoryReasonCode.PRESEED_SOURCE_MAPPING_MISSING,
                "synthetic-s1-root");

        ObjectNode ambiguousMapping = fixture("valid-s1-s2-candidates.json");
        record(ambiguousMapping, 0).put("sourceMappingState", "AMBIGUOUS");
        recalculateChecksum(ambiguousMapping);
        assertQuarantined(validate(ambiguousMapping), PreseedInventoryReasonCode.PRESEED_SOURCE_MAPPING_AMBIGUOUS,
                "synthetic-s1-root");

        ObjectNode conflictingMapping = fixture("valid-s1-s2-candidates.json");
        record(conflictingMapping, 0).put("sourceMappingState", "CONFLICT");
        recalculateChecksum(conflictingMapping);
        assertQuarantined(validate(conflictingMapping), PreseedInventoryReasonCode.PRESEED_SOURCE_MAPPING_CONFLICT,
                "synthetic-s1-root");

        ObjectNode clientAppConflict = fixture("valid-s1-s2-candidates.json");
        record(clientAppConflict, 1).put("clientAppConflict", true);
        recalculateChecksum(clientAppConflict);
        assertQuarantined(validate(clientAppConflict), PreseedInventoryReasonCode.PRESEED_CLIENT_APP_CONFLICT,
                "synthetic-s2-platform");

        ObjectNode missingAuthority = fixture("valid-s1-s2-candidates.json");
        record(missingAuthority, 0).put("authorityFactsComplete", false);
        recalculateChecksum(missingAuthority);
        assertQuarantined(validate(missingAuthority), PreseedInventoryReasonCode.PRESEED_AUTHORITY_FACTS_MISSING,
                "synthetic-s1-root");

        ObjectNode revoked = fixture("valid-s1-s2-candidates.json");
        record(revoked, 0).put("revoked", true);
        recalculateChecksum(revoked);
        assertQuarantined(validate(revoked), PreseedInventoryReasonCode.PRESEED_CREDENTIAL_REVOKED,
                "synthetic-s1-root");

        ObjectNode expired = fixture("valid-s1-s2-candidates.json");
        record(expired, 0).put("expiresAt", "2000-01-01T00:00:00Z");
        recalculateChecksum(expired);
        assertQuarantined(validate(expired), PreseedInventoryReasonCode.PRESEED_CREDENTIAL_EXPIRED,
                "synthetic-s1-root");

        ObjectNode expiryAtValidationInstant = fixture("valid-s1-s2-candidates.json");
        record(expiryAtValidationInstant, 0).put("expiresAt", FIXED_NOW.toString());
        recalculateChecksum(expiryAtValidationInstant);
        assertQuarantined(validate(expiryAtValidationInstant), PreseedInventoryReasonCode.PRESEED_CREDENTIAL_EXPIRED,
                "synthetic-s1-root");

        ObjectNode noExpiry = fixture("valid-s1-s2-candidates.json");
        record(noExpiry, 0).putNull("expiresAt");
        recalculateChecksum(noExpiry);
        assertQuarantined(validate(noExpiry), PreseedInventoryReasonCode.PRESEED_CREDENTIAL_EXPIRY_REQUIRED,
                "synthetic-s1-root");

        ObjectNode invalidFingerprint = fixture("valid-s1-s2-candidates.json");
        record(invalidFingerprint, 0).put("credentialFingerprintPrefix", "not-a-fingerprint");
        recalculateChecksum(invalidFingerprint);
        assertQuarantined(validate(invalidFingerprint), PreseedInventoryReasonCode.PRESEED_FINGERPRINT_INVALID,
                "synthetic-s1-root");
    }

    @Test
    void quarantinesDuplicateAndConflictingTenantAuthorityAndMissingS2Facts() throws Exception {
        ObjectNode duplicate = fixture("valid-s1-s2-candidates.json");
        ObjectNode duplicateRecord = record(duplicate, 1).deepCopy();
        duplicateRecord.put("recordAlias", "synthetic-s2-platform-duplicate");
        duplicateRecord.put("credentialFingerprintPrefix", "c1d2e3f4");
        duplicateRecord.put("credentialFingerprintSuffix", "a5b6c7d8");
        records(duplicate).add(duplicateRecord);
        recalculateChecksum(duplicate);
        assertQuarantined(validate(duplicate), PreseedInventoryReasonCode.PRESEED_TENANT_AUTHORITY_DUPLICATE,
                "synthetic-s2-platform-duplicate");

        ObjectNode conflict = fixture("valid-s1-s2-candidates.json");
        ObjectNode conflictingRecord = record(conflict, 1).deepCopy();
        conflictingRecord.put("recordAlias", "synthetic-s2-platform-conflict");
        conflictingRecord.put("credentialFingerprintPrefix", "d1e2f3a4");
        conflictingRecord.put("credentialFingerprintSuffix", "b5c6d7e8");
        conflictingRecord.put("tenantAuthorityReference", "synthetic-s2-tenant-authority-other");
        records(conflict).add(conflictingRecord);
        recalculateChecksum(conflict);
        assertQuarantined(validate(conflict), PreseedInventoryReasonCode.PRESEED_TENANT_AUTHORITY_CONFLICT,
                "synthetic-s2-platform-conflict");

        ObjectNode missingS2Authority = fixture("valid-s1-s2-candidates.json");
        record(missingS2Authority, 1).put("tenantAuthorityState", "MISSING");
        recalculateChecksum(missingS2Authority);
        assertQuarantined(validate(missingS2Authority), PreseedInventoryReasonCode.PRESEED_AUTHORITY_FACTS_MISSING,
                "synthetic-s2-platform");

        ObjectNode conflictingClientApp = fixture("valid-s1-s2-candidates.json");
        ObjectNode sameClientAppDifferentTenant = record(conflictingClientApp, 1).deepCopy();
        sameClientAppDifferentTenant.put("recordAlias", "synthetic-s2-client-app-conflict");
        sameClientAppDifferentTenant.put("tenantReference", "synthetic-s2-other-tenant");
        sameClientAppDifferentTenant.put("tenantAuthorityReference", "synthetic-s2-other-tenant-authority");
        sameClientAppDifferentTenant.put("credentialFingerprintPrefix", "e1f2a3b4");
        sameClientAppDifferentTenant.put("credentialFingerprintSuffix", "c5d6e7f8");
        records(conflictingClientApp).add(sameClientAppDifferentTenant);
        recalculateChecksum(conflictingClientApp);
        assertQuarantined(validate(conflictingClientApp), PreseedInventoryReasonCode.PRESEED_CLIENT_APP_CONFLICT,
                "synthetic-s2-client-app-conflict");
    }

    @Test
    void legacyDataCannotPromoteAPrincipalOrCredentialLaneAndNeedsExplicitReview() throws Exception {
        ObjectNode prohibitedPromotion = fixture("valid-s1-s2-candidates.json");
        ObjectNode prohibitedRecord = record(prohibitedPromotion, 0);
        prohibitedRecord.put("sourceKind", "LEGACY_UPSTREAM_ADMIN");
        prohibitedRecord.put("proposedDisposition", "REQUIRES_APPROVAL");
        recalculateChecksum(prohibitedPromotion);
        assertQuarantined(validate(prohibitedPromotion), PreseedInventoryReasonCode.PRESEED_LEGACY_PROMOTION_PROHIBITED,
                "synthetic-s1-root");

        ObjectNode requiresApproval = fixture("valid-s1-s2-candidates.json");
        ObjectNode legacyRecord = record(requiresApproval, 0);
        legacyRecord.put("sourceKind", "LEGACY_SCOPE");
        legacyRecord.put("proposedPrincipalType", "UNSPECIFIED");
        legacyRecord.put("proposedCredentialLane", "UNSPECIFIED");
        legacyRecord.put("proposedDisposition", "REQUIRES_APPROVAL");
        recalculateChecksum(requiresApproval);
        assertQuarantined(validate(requiresApproval), PreseedInventoryReasonCode.PRESEED_LEGACY_REQUIRES_APPROVAL,
                "synthetic-s1-root");

        ObjectNode tenantListRequiresApproval = fixture("valid-s1-s2-candidates.json");
        ObjectNode tenantListRecord = record(tenantListRequiresApproval, 0);
        tenantListRecord.put("sourceKind", "LEGACY_TENANT_LIST");
        tenantListRecord.put("proposedPrincipalType", "UNSPECIFIED");
        tenantListRecord.put("proposedCredentialLane", "UNSPECIFIED");
        tenantListRecord.put("proposedDisposition", "REQUIRES_APPROVAL");
        recalculateChecksum(tenantListRequiresApproval);
        assertQuarantined(validate(tenantListRequiresApproval),
                PreseedInventoryReasonCode.PRESEED_LEGACY_REQUIRES_APPROVAL, "synthetic-s1-root");
    }

    @Test
    void quarantinedAndApprovalRequiredDispositionsNeverBecomeValid() throws Exception {
        ObjectNode requiresApproval = fixture("valid-s1-s2-candidates.json");
        record(requiresApproval, 0).put("proposedDisposition", "REQUIRES_APPROVAL");
        recalculateChecksum(requiresApproval);
        assertQuarantined(validate(requiresApproval), PreseedInventoryReasonCode.PRESEED_DISPOSITION_REQUIRES_APPROVAL,
                "synthetic-s1-root");

        ObjectNode explicitlyQuarantined = fixture("valid-s1-s2-candidates.json");
        record(explicitlyQuarantined, 0).put("proposedDisposition", "QUARANTINED");
        record(explicitlyQuarantined, 0).put("quarantineReason", "synthetic-unresolved-conflict");
        recalculateChecksum(explicitlyQuarantined);
        assertQuarantined(validate(explicitlyQuarantined),
                PreseedInventoryReasonCode.PRESEED_RECORD_DECLARED_QUARANTINE, "synthetic-s1-root");
    }

    @Test
    void fixturesAndReadmeRemainSyntheticAndChecksumConsistent() throws Exception {
        String readme = fixtureText("README.md");
        assertTrue(readme.contains("428d140a6f31f353815de24bfdbfaa2b2f6ea5a0083e05be0e917f3d18519a07"));
        assertTrue(readme.contains("3843b890cff39d0e07718ec6fbd8face109f360cdf0361c620dd4188d16936db"));
        assertTrue(readme.contains("not an owner approval"));

        for (String fixtureName : List.of(
                "valid-s1-s2-candidates.json",
                "quarantined-owner-conflict.json",
                "invalid-checksum.json")) {
            String fixtureText = fixtureText(fixtureName);
            assertTrue(fixtureText.contains("synthetic-"));
            assertFalse(fixtureText.contains("foggy-world-sim"));
            assertFalse(fixtureText.contains("NAVI_ADMIN_API_KEY"));
            assertFalse(fixtureText.contains("NAVI_CONTROL_API_KEY"));
        }

        ObjectNode valid = fixture("valid-s1-s2-candidates.json");
        assertEquals(valid.path("checksum").textValue(), PreseedInventoryCanonicalizer.checksum(valid));
        ObjectNode quarantined = fixture("quarantined-owner-conflict.json");
        assertEquals(quarantined.path("checksum").textValue(), PreseedInventoryCanonicalizer.checksum(quarantined));
    }

    @Test
    void validatorImplementationHasNoRuntimeIntegrationDependenciesByConstruction() throws Exception {
        Path sourceRoot = Path.of("src/main/java/com/foggy/navigator/common/authorization/preseed");
        assertTrue(Files.isDirectory(sourceRoot));

        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source, StandardCharsets.UTF_8);
                assertFalse(content.contains("org.springframework"), source.toString());
                assertFalse(content.contains("jakarta.persistence"), source.toString());
                assertFalse(content.contains("java.sql."), source.toString());
                assertFalse(content.contains("java.net."), source.toString());
                assertFalse(content.contains("java.nio.file"), source.toString());
                assertFalse(content.contains("System.getenv"), source.toString());
                assertFalse(content.contains("System.getProperty"), source.toString());
                assertFalse(content.contains("Instant.now("), source.toString());
                assertFalse(content.contains(".navigator/"), source.toString());
                assertFalse(content.contains(".navigator\\\\"), source.toString());
                assertFalse(content.contains("HttpClient"), source.toString());
                assertFalse(content.contains("RestTemplate"), source.toString());
                assertFalse(content.contains("WebClient"), source.toString());
            }
        }
    }

    private static ObjectNode fixture(String name) throws IOException {
        try (InputStream input = PreseedInventoryValidatorTest.class.getResourceAsStream(FIXTURE_ROOT + name)) {
            assertNotNull(input, name);
            JsonNode root = MAPPER.readTree(input);
            assertTrue(root.isObject(), name);
            return (ObjectNode) root;
        }
    }

    private static String fixtureText(String name) throws IOException {
        try (InputStream input = PreseedInventoryValidatorTest.class.getResourceAsStream(FIXTURE_ROOT + name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private PreseedInventoryValidationResult validate(ObjectNode document) throws Exception {
        return validator.validate(MAPPER.writeValueAsString(document));
    }

    private static void recalculateChecksum(ObjectNode document) {
        document.put("checksum", PreseedInventoryCanonicalizer.checksum(document));
    }

    private static ArrayNode records(ObjectNode document) {
        return (ArrayNode) document.path("records");
    }

    private static ObjectNode record(ObjectNode document, int index) {
        return (ObjectNode) records(document).get(index);
    }

    private static void assertInvalid(PreseedInventoryValidationResult result,
                                      PreseedInventoryReasonCode reasonCode) {
        assertEquals(PreseedInventoryClassification.INVALID, result.classification());
        assertEquals(reasonCode, result.reasonCode());
        assertNull(result.recordAlias());
    }

    private static void assertQuarantined(PreseedInventoryValidationResult result,
                                          PreseedInventoryReasonCode reasonCode,
                                          String alias) {
        assertEquals(PreseedInventoryClassification.QUARANTINED, result.classification());
        assertEquals(reasonCode, result.reasonCode());
        assertEquals(alias, result.recordAlias());
    }
}
