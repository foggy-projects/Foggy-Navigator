package com.foggy.navigator.common.authorization.preseed;

import java.util.Set;

/** Fixed, offline-only schema constants for the P1B-B0 inventory envelope. */
public final class PreseedInventorySchemaV1 {

    public static final String SCHEMA_VERSION = "navi.authorization.preseed-inventory.v1";
    public static final String MODE_OFFLINE_VALIDATE_ONLY = "OFFLINE_VALIDATE_ONLY";

    static final Set<String> ENVELOPE_FIELDS = Set.of(
            "schemaVersion",
            "mode",
            "deployment",
            "records",
            "checksum");

    static final Set<String> DEPLOYMENT_FIELDS = Set.of(
            "navigatorInstanceId",
            "environmentProfile");

    static final Set<String> RECORD_FIELDS = Set.of(
            "recordAlias",
            "sourceKind",
            "upstreamSystemReference",
            "tenantReference",
            "clientAppReference",
            "clientAppConflict",
            "namespaceReference",
            "ownerReference",
            "ownerConflict",
            "sourceMappingState",
            "authorityFactsComplete",
            "tenantAuthorityReference",
            "tenantAuthorityState",
            "credentialStatus",
            "expiresAt",
            "revoked",
            "credentialFingerprintPrefix",
            "credentialFingerprintSuffix",
            "proposedPrincipalType",
            "proposedCredentialLane",
            "proposedDisposition",
            "quarantineReason",
            "approvalReference");

    static final Set<String> SOURCE_KINDS = Set.of(
            "S1_INSTANCE_ROOT_DECLARATION",
            "S2_SAAS_PLATFORM_DECLARATION",
            "LEGACY_UPSTREAM_ADMIN",
            "LEGACY_SCOPE",
            "LEGACY_TENANT_LIST");

    static final Set<String> PRINCIPAL_TYPES = Set.of(
            "INSTANCE_ROOT",
            "SAAS_PLATFORM",
            "SAAS_PROVISIONING",
            "SAAS_SECURITY_ADMIN",
            "CLIENT_APP",
            "UNSPECIFIED");

    static final Set<String> CREDENTIAL_LANES = Set.of(
            "INSTANCE_ROOT_CONTROL",
            "INSTANCE_ROOT_SECURITY",
            "SAAS_PROVISIONING",
            "SAAS_SECURITY_ADMIN",
            "CLIENT_APP_CONTROL",
            "CLIENT_APP_RUNTIME_CREDENTIAL",
            "CLIENT_APP_RUNTIME_ACCESS",
            "UNSPECIFIED");

    static final Set<String> DISPOSITIONS = Set.of(
            "CANDIDATE",
            "REQUIRES_APPROVAL",
            "QUARANTINED");

    static final Set<String> MAPPING_STATES = Set.of(
            "VERIFIED",
            "MISSING",
            "AMBIGUOUS",
            "CONFLICT");

    private PreseedInventorySchemaV1() {
    }
}
