package com.foggy.navigator.session.lifecycle;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleMigrationContractTest {

    @Test
    void forwardMigrationIsAdditiveAndRollbackKeepsEnforcementFenceExplicit()
            throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String forward = Files.readString(root.resolve(
                "docs/migration/2026-07-30-arch-001-lifecycle-owner.sql"));
        String rollback = Files.readString(root.resolve(
                "docs/migration/2026-07-30-arch-001-lifecycle-owner-rollback.sql"));
        String remediation = Files.readString(root.resolve(
                "docs/migration/2026-07-31-arch-001-third-remediation.sql"));
        String activation = Files.readString(root.resolve(
                "docs/migration/2026-08-01-arch-001-activation-readiness.sql"));
        String boundedLocalDevelopment = Files.readString(root.resolve(
                "docs/migration/2026-08-02-arch-001-bounded-local-development-activation.sql"));
        assertThat(forward).doesNotContain("DROP TABLE", "DELETE FROM", "UPDATE ");
        assertThat(forward.split("CREATE TABLE IF NOT EXISTS", -1).length - 1)
                .isEqualTo(12);
        assertThat(rollback)
                .contains("must not be used after any real ENFORCED aggregate exists");
        assertThat(remediation)
                .doesNotContain("DROP TABLE", "DELETE FROM", "UPDATE ")
                .contains("information_schema.columns",
                        "binding_digest_version",
                        "client_request_id",
                        "quarantine_cursor");
        assertThat(activation)
                .doesNotContain("DELETE FROM", "UPDATE ")
                .contains(
                        "CREATE TABLE IF NOT EXISTS lifecycle_activation_targets",
                        "active_slot",
                        "uk_lwg_active_slot",
                        "controller_inventory_digest",
                        "expires_at");
        assertThat(boundedLocalDevelopment)
                .doesNotContain("DROP TABLE", "DELETE FROM", "UPDATE ")
                .contains("MODIFY COLUMN codex_home_key VARCHAR(256) NULL");
        assertThat(rollback)
                .contains("lifecycle_activation_targets",
                        "status NOT IN ('CLOSED', 'DESTROYED')");
    }
}
