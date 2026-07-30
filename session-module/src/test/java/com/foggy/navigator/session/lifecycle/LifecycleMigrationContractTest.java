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
        assertThat(forward).doesNotContain("DROP TABLE", "DELETE FROM", "UPDATE ");
        assertThat(forward.split("CREATE TABLE IF NOT EXISTS", -1).length - 1)
                .isEqualTo(12);
        assertThat(rollback)
                .contains("must not be used after any real ENFORCED aggregate exists");
    }
}
