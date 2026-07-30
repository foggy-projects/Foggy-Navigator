package com.foggy.navigator.session.lifecycle;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repo-owned ephemeral Slice 8 fixture. It creates its own in-memory database,
 * fake controller evidence and aggregate identifiers; it opens no shared port.
 */
class IsolatedEnforcedLifecycleContractTest {

    private static final Set<String> CAPABILITIES = Set.of(
            "AUTHENTICATED_LIFECYCLE_V1",
            "FENCED_INVENTORY_V1",
            "DURABLE_LIFECYCLE_FACTS_V1",
            "MONOTONIC_ACK_V1",
            "EXACT_DISPATCH_DEDUPE_V1",
            "DURABLE_PROVIDER_TASK_ID_V1",
            "TERMINATION_ATOMIC_CAPABILITY_V1");

    @Test
    void enforcedFixtureQuarantinesOnProofLossAndConvergesAfterRecovery()
            throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        try (Connection db = DriverManager.getConnection(
                "jdbc:h2:mem:arch001_" + suffix + ";DB_CLOSE_DELAY=-1");
             Statement sql = db.createStatement()) {
            sql.execute("create table fixture_proof("
                    + "proof_id varchar(96) primary key, status varchar(24), refs int, "
                    + "unfinished_outbox int)");
            sql.execute("insert into fixture_proof values('proof-fixture','ACTIVE',3,1)");

            FakeController controller = new FakeController(true, true);
            LocalDateTime now = LocalDateTime.parse("2026-07-30T12:00:00");
            LifecycleEnrollmentGate gate = new LifecycleEnrollmentGate();
            LifecycleEnrollmentGate.EnrollmentRequest request =
                    request(controller, now, true, now.plusMinutes(2));
            assertThat(gate.evaluate(request).enrolled()).isTrue();

            controller.exclusive = false;
            sql.execute("update fixture_proof set status='QUARANTINED'");
            assertThat(gate.evaluate(request(controller, now, false, now.minusSeconds(1)))
                    .safeReasonCode()).isEqualTo("LIFECYCLE_WRITER_EXCLUSIVITY_LOST");

            controller.exclusive = true;
            sql.execute("update fixture_proof set status='ACTIVE', "
                    + "refs=0, unfinished_outbox=0");
            assertThat(gate.evaluate(request(
                    controller, now.plusSeconds(5), true, now.plusMinutes(2))).enrolled())
                    .isTrue();
            ResultSet remaining = sql.executeQuery(
                    "select refs, unfinished_outbox from fixture_proof");
            assertThat(remaining.next()).isTrue();
            assertThat(remaining.getInt(1)).isZero();
            assertThat(remaining.getInt(2)).isZero();
        }
    }

    @Test
    void nonFixtureEnrollmentRemainsDisabledWithoutRealActivationEvidence() {
        LocalDateTime now = LocalDateTime.parse("2026-07-30T12:00:00");
        LifecycleEnrollmentGate.EnrollmentRequest request =
                new LifecycleEnrollmentGate.EnrollmentRequest(
                        "codex-biz-worker", true, false, false, true, true, true,
                        true, true, true, CAPABILITIES, true,
                        now.plusMinutes(1), now);
        assertThat(new LifecycleEnrollmentGate().evaluate(request).safeReasonCode())
                .isEqualTo(LifecycleSchemaReadiness.ACTIVATION_DISABLED);
    }

    private LifecycleEnrollmentGate.EnrollmentRequest request(
            FakeController controller,
            LocalDateTime now,
            boolean proofActive,
            LocalDateTime expires) {
        return new LifecycleEnrollmentGate.EnrollmentRequest(
                "codex-biz-worker",
                controller.targetBinaryHomogeneous,
                true,
                false,
                true,
                true,
                true,
                true,
                true,
                true,
                CAPABILITIES,
                proofActive && controller.exclusive,
                expires,
                now);
    }

    private static final class FakeController {
        private final boolean targetBinaryHomogeneous;
        private boolean exclusive;
        private FakeController(boolean homogeneous, boolean exclusive) {
            this.targetBinaryHomogeneous = homogeneous;
            this.exclusive = exclusive;
        }
    }
}
