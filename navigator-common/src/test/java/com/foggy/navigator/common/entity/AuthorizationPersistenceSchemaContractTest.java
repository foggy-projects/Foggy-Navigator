package com.foggy.navigator.common.entity;

import com.foggy.navigator.common.repository.AuthorizationDecisionRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationPersistenceSchemaContractTest {

    private static final List<Class<?>> AUTHORIZATION_ENTITIES = List.of(
            AuthorizationPrincipalEntity.class,
            AuthorizationCredentialEntity.class,
            AuthorizationManagementTokenEntity.class,
            AuthorizationPlatformGrantEntity.class,
            AuthorizationTenantAuthorityEntity.class,
            AuthorizationDecisionEntity.class
    );

    @Test
    void mapsTheSixApprovedAdditiveAuthorizationTablesWithoutJpaAssociations() {
        assertTable(AuthorizationPrincipalEntity.class, "authorization_principal");
        assertTable(AuthorizationCredentialEntity.class, "authorization_credential");
        assertTable(AuthorizationManagementTokenEntity.class, "authorization_management_token");
        assertTable(AuthorizationPlatformGrantEntity.class, "authorization_platform_grant");
        assertTable(AuthorizationTenantAuthorityEntity.class, "authorization_tenant_authority");
        assertTable(AuthorizationDecisionEntity.class, "authorization_decision");

        for (Class<?> entity : AUTHORIZATION_ENTITIES) {
            assertFalse(Arrays.stream(entity.getDeclaredFields())
                    .anyMatch(field -> field.isAnnotationPresent(OneToOne.class)
                            || field.isAnnotationPresent(OneToMany.class)
                            || field.isAnnotationPresent(ManyToOne.class)
                            || field.isAnnotationPresent(ManyToMany.class)),
                    () -> entity.getSimpleName() + " must retain opaque-id JPA composition");
        }
    }

    @Test
    void mapsRequiredScopeUniquenessAndConcurrencyContracts() throws Exception {
        assertColumn(AuthorizationPrincipalEntity.class, "principalRecordId", "principal_record_id", false);
        assertId(AuthorizationPrincipalEntity.class, "principalRecordId");
        assertUniqueConstraint(AuthorizationPrincipalEntity.class, "uk_auth_principal_scope",
                "navigator_instance_id", "principal_type", "principal_id");
        assertVersion(AuthorizationPrincipalEntity.class, "rowVersion");

        assertId(AuthorizationCredentialEntity.class, "credentialId");
        assertColumn(AuthorizationCredentialEntity.class, "verifierReference", "verifier_reference", false);
        assertIndex(AuthorizationCredentialEntity.class, "idx_auth_credential_principal_lane_status_exp",
                "principal_id,credential_lane,status,expires_at", false);
        assertIndex(AuthorizationCredentialEntity.class, "idx_auth_credential_verifier_ref",
                "verifier_reference", true);
        assertVersion(AuthorizationCredentialEntity.class, "rowVersion");

        assertId(AuthorizationManagementTokenEntity.class, "tokenId");
        assertUniqueConstraint(AuthorizationManagementTokenEntity.class, "uk_auth_mgmt_token_hash", "token_hash");
        assertUniqueConstraint(AuthorizationManagementTokenEntity.class, "uk_auth_mgmt_token_ref", "token_reference");
        assertUniqueConstraint(AuthorizationManagementTokenEntity.class, "uk_auth_mgmt_security_nonce", "security_action_nonce");
        assertIndex(AuthorizationManagementTokenEntity.class, "idx_auth_mgmt_token_credential_purpose_status_exp",
                "credential_id,purpose,status,expires_at", false);
        assertVersion(AuthorizationManagementTokenEntity.class, "rowVersion");

        assertId(AuthorizationPlatformGrantEntity.class, "platformGrantId");
        assertUniqueConstraint(AuthorizationPlatformGrantEntity.class, "uk_auth_platform_grant_scope",
                "navigator_instance_id", "environment_profile", "principal_id", "upstream_system_id");
        assertVersion(AuthorizationPlatformGrantEntity.class, "rowVersion");

        assertId(AuthorizationTenantAuthorityEntity.class, "tenantAuthorityId");
        assertUniqueConstraint(AuthorizationTenantAuthorityEntity.class, "uk_auth_tenant_authority_scope",
                "navigator_instance_id", "tenant_id");
        assertVersion(AuthorizationTenantAuthorityEntity.class, "rowVersion");
    }

    @Test
    void mapsTheDecisionAuditAsAppendOnlyRedactedDataAndAQueryOnlyRepository() throws Exception {
        assertId(AuthorizationDecisionEntity.class, "decisionId");
        for (Field field : AuthorizationDecisionEntity.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                assertFalse(column.updatable(), () -> field.getName() + " must be immutable after append");
            }
        }
        assertColumn(AuthorizationDecisionEntity.class, "navigatorInstanceId", "navigator_instance_id", true);
        assertColumn(AuthorizationDecisionEntity.class, "environmentProfile", "environment_profile", true);
        assertColumn(AuthorizationDecisionEntity.class, "principalFingerprint", "principal_fingerprint", true);
        assertColumn(AuthorizationDecisionEntity.class, "credentialFingerprint", "credential_fingerprint", true);
        assertColumn(AuthorizationDecisionEntity.class, "targetFingerprint", "target_fingerprint", true);
        assertColumn(AuthorizationDecisionEntity.class, "requestDigest", "request_digest", true);
        assertColumn(AuthorizationDecisionEntity.class, "impactDigest", "impact_digest", true);
        assertIndex(AuthorizationDecisionEntity.class, "idx_auth_decision_correlation", "correlation_id", false);
        assertIndex(AuthorizationDecisionEntity.class, "idx_auth_decision_principal",
                "principal_type,principal_fingerprint", false);
        assertIndex(AuthorizationDecisionEntity.class, "idx_auth_decision_credential",
                "credential_lane,credential_fingerprint", false);
        assertIndex(AuthorizationDecisionEntity.class, "idx_auth_decision_action", "action_id", false);
        assertIndex(AuthorizationDecisionEntity.class, "idx_auth_decision_target",
                "target_type,target_fingerprint", false);
        assertIndex(AuthorizationDecisionEntity.class, "idx_auth_decision_result_reason",
                "decision,reason_code", false);
        assertIndex(AuthorizationDecisionEntity.class, "idx_auth_decision_evaluated_at", "evaluated_at", false);

        assertTrue(Repository.class.isAssignableFrom(AuthorizationDecisionRepository.class));
        assertFalse(CrudRepository.class.isAssignableFrom(AuthorizationDecisionRepository.class));
        assertNotNull(AuthorizationDecisionRepository.class.getMethod("append", AuthorizationDecisionEntity.class));
        assertFalse(Arrays.stream(AuthorizationDecisionRepository.class.getMethods())
                .anyMatch(method -> method.getName().startsWith("delete") || method.getName().equals("save")),
                "decision audit repository must not expose generic update/delete operations");
    }

    @Test
    void forwardMigrationIsAdditiveIdempotentAndCoversAllMappings() throws IOException {
        String sql = readRepositoryFile("docs/migration/2026-07-19-gov-001-authorization-foundation.sql");
        String normalized = sql.toLowerCase(Locale.ROOT);

        for (String table : List.of(
                "authorization_principal",
                "authorization_credential",
                "authorization_management_token",
                "authorization_platform_grant",
                "authorization_tenant_authority",
                "authorization_decision")) {
            assertTrue(normalized.contains("create table if not exists " + table), () -> "missing table " + table);
        }
        assertEquals(6, occurrences(normalized, "create table if not exists authorization_"));
        assertFalse(normalized.matches("(?s).*\\balter\\s+table\\b.*"));
        assertFalse(normalized.matches("(?s).*\\b(insert|update|delete|merge)\\s+(into|from)?\\s*authorization_.*"));
        assertTrue(normalized.contains("uk_auth_principal_scope"));
        assertTrue(normalized.contains("idx_auth_credential_principal_lane_status_exp"));
        assertTrue(normalized.contains("uk_auth_mgmt_security_nonce"));
        assertTrue(normalized.contains("uk_auth_platform_grant_scope"));
        assertTrue(normalized.contains("uk_auth_tenant_authority_scope"));
        assertTrue(normalized.contains("idx_auth_decision_result_reason"));
        String decisionTable = tableDefinition(normalized, "authorization_decision");
        assertTrue(decisionTable.contains("navigator_instance_id varchar(64) not null"));
        assertTrue(decisionTable.contains("environment_profile varchar(32) not null"));
        assertFalse(normalized.contains("upstream_client_app_admin_credential"));
        assertFalse(normalized.contains("client_app_control_credential"));
        assertFalse(normalized.contains("client_app_runtime_credential"));
        assertFalse(normalized.contains("business_task_scoped_token"));
    }

    @Test
    void rollbackIsExplicitlyDestructiveAndDoesNotAlterLegacyOrIssueData() throws IOException {
        String sql = readRepositoryFile("docs/migration/2026-07-19-gov-001-authorization-foundation-rollback.sql");
        String normalized = sql.toLowerCase(Locale.ROOT);

        assertTrue(normalized.contains("destructive rollback"));
        assertEquals(6, occurrences(normalized, "drop table if exists authorization_"));
        assertFalse(normalized.matches("(?s).*\\balter\\s+table\\b.*"));
        assertFalse(normalized.matches("(?s).*\\b(insert|update|delete|merge)\\s+(into|from)?\\s*.*"));
        assertFalse(normalized.contains("upstream_client_app_admin_credential"));
        assertFalse(normalized.contains("client_app_control_credential"));
        assertFalse(normalized.contains("client_app_runtime_credential"));
        assertFalse(normalized.contains("business_task_scoped_token"));
    }

    @Test
    void productionProfileContinuesToUseValidateForPreAppliedSchema() throws IOException {
        String yaml = readRepositoryFile("launcher/src/main/resources/application-prod.yml").toLowerCase(Locale.ROOT);
        assertTrue(yaml.contains("ddl-auto: validate"));
    }

    private static void assertTable(Class<?> entity, String expectedName) {
        Table table = entity.getAnnotation(Table.class);
        assertNotNull(table, () -> entity.getSimpleName() + " must declare a table mapping");
        assertEquals(expectedName, table.name());
    }

    private static void assertId(Class<?> entity, String fieldName) throws Exception {
        assertNotNull(entity.getDeclaredField(fieldName).getAnnotation(Id.class));
    }

    private static void assertVersion(Class<?> entity, String fieldName) throws Exception {
        assertNotNull(entity.getDeclaredField(fieldName).getAnnotation(Version.class));
    }

    private static void assertColumn(Class<?> entity, String fieldName, String expectedName, boolean immutable) throws Exception {
        Column column = entity.getDeclaredField(fieldName).getAnnotation(Column.class);
        assertNotNull(column, () -> entity.getSimpleName() + "." + fieldName + " must be a mapped column");
        assertEquals(expectedName, column.name());
        if (immutable) {
            assertFalse(column.updatable());
        }
    }

    private static void assertUniqueConstraint(Class<?> entity, String name, String... columns) {
        assertTrue(Arrays.stream(entity.getAnnotation(Table.class).uniqueConstraints())
                .anyMatch(constraint -> constraint.name().equals(name)
                        && Arrays.equals(columns, constraint.columnNames())),
                () -> "missing unique constraint " + name);
    }

    private static void assertIndex(Class<?> entity, String name, String columnList, boolean unique) {
        assertTrue(Arrays.stream(entity.getAnnotation(Table.class).indexes())
                .anyMatch(index -> index.name().equals(name)
                        && index.columnList().equals(columnList)
                        && index.unique() == unique),
                () -> "missing index " + name);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidateRoot : List.of(workingDirectory, workingDirectory.getParent())) {
            if (candidateRoot != null) {
                Path candidate = candidateRoot.resolve(relativePath);
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate);
                }
            }
        }
        throw new IOException("Cannot locate repository file " + relativePath + " from " + workingDirectory);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int position = 0;
        while ((position = value.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }

    private static String tableDefinition(String sql, String table) {
        String startMarker = "create table if not exists " + table;
        int start = sql.indexOf(startMarker);
        assertTrue(start >= 0, () -> "missing table definition for " + table);
        int end = sql.indexOf(") engine=innodb", start);
        assertTrue(end >= 0, () -> "missing table terminator for " + table);
        return sql.substring(start, end);
    }
}
