package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.session.controller.LifecycleActivationController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleActivationAuthorityContractTest {

    @Test
    void legacyCallerCannotTurnFixtureGateIntoProductionAuthority() {
        var request = new LifecycleEnrollmentGate.EnrollmentRequest(
                "codex-biz-worker",
                true,
                false,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                Set.of(
                        "AUTHENTICATED_LIFECYCLE_V1",
                        "FENCED_INVENTORY_V1",
                        "DURABLE_LIFECYCLE_FACTS_V1",
                        "MONOTONIC_ACK_V1",
                        "EXACT_DISPATCH_DEDUPE_V1",
                        "DURABLE_PROVIDER_TASK_ID_V1",
                        "TERMINATION_ATOMIC_CAPABILITY_V1"),
                true,
                LocalDateTime.now().plusMinutes(1),
                LocalDateTime.now());

        assertThat(new LifecycleEnrollmentGate().evaluate(request).safeReasonCode())
                .isEqualTo(LifecycleActivationReason.AUTHORITY_REQUIRED);
    }

    @Test
    void productionAdmissionCarriesIdentityOnlyAndNoSelfReportedAuthority() {
        Set<String> componentNames = Arrays.stream(
                        LifecycleProductionAdmissionService.ProductionAdmissionRequest.class
                                .getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(componentNames)
                .contains(
                        "providerType", "tenantId", "userId", "physicalWorkerId",
                        "sessionId", "taskId", "modelConfigId", "model",
                        "existingSessionId", "promptSha256")
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT)
                        .matches(".*(fixture|evidence|ready|proof|allowlist).*"));
    }

    @Test
    void activationAndControlAreClosedByDefault() {
        LifecycleActivationProperties properties =
                new LifecycleActivationProperties();

        assertThat(properties.isAdmissionEnabled()).isFalse();
        assertThat(properties.isControlEnabled()).isFalse();
        assertThat(properties.getExactTargetId()).isNull();
        assertThat(properties.getControlToken()).isNull();
    }

    @Test
    void controlAuthorityIsTargetScopedAndCannotBeSubstitutedByOtherTokens() {
        LifecycleActivationProperties properties =
                new LifecycleActivationProperties();
        properties.setControlEnabled(true);
        properties.setExactTargetId("arch001-act-target");
        properties.setControlToken("c".repeat(48));
        LifecycleActivationControlAuthorizer authorizer =
                new LifecycleActivationControlAuthorizer(properties);

        authorizer.requireAuthorized(
                "arch001-act-target", "c".repeat(48));
        assertThatThrownBy(() -> authorizer.requireAuthorized(
                "arch001-act-target", "runtime-or-admin-token"))
                .hasMessage(LifecycleActivationReason.CONTROL_UNAUTHORIZED);
        assertThatThrownBy(() -> authorizer.requireAuthorized(
                "another-target", "c".repeat(48)))
                .hasMessage(LifecycleActivationReason.TARGET_MISMATCH);
    }

    @Test
    void operatorSurfaceAcceptsNoRequestBodyOrAuthorityBooleans() {
        for (var method : LifecycleActivationController.class
                .getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertThat(Arrays.stream(method.getParameterTypes()))
                    .allMatch(type -> type == String.class);
        }
    }

    @Test
    void controllerDigestMatchesPythonCanonicalVector() {
        String runId = "arch001-act-run-20260801";
        String head = "fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00";
        List<LifecycleActivationManifest.Controller> controllers = List.of(
                controller("process", "target-process-set", "DISABLED", runId, head),
                controller("supervisor", "none", "NOT_APPLICABLE", runId, head),
                controller("manual_launcher", "target-pidfiles", "DISABLED", runId, head),
                controller("ci", "none", "NOT_APPLICABLE", runId, head),
                controller("timer", "none", "NOT_APPLICABLE", runId, head),
                controller("docker", "mysql-compose", "DISABLED", runId, head));

        String digest = new FileLifecycleActivationArtifactSource(
                new LifecycleActivationProperties(), new ObjectMapper())
                .controllerInventoryDigest(controllers);

        assertThat(digest).isEqualTo(
                "5f083bef48905d8d086b1cd70adad38085b235e8ae40c235dbf5728b5f0d19b1");
    }

    private LifecycleActivationManifest.Controller controller(
            String kind, String id, String state, String runId, String head) {
        String source = switch (kind) {
            case "process" -> "proc-cwd-scan";
            case "supervisor" -> "local-target-no-supervisor";
            case "manual_launcher" -> "target-pidfile-scan";
            case "ci" -> "local-target-no-ci";
            case "timer" -> "local-target-no-timer";
            case "docker" -> "compose-label-scan";
            default -> throw new IllegalArgumentException(kind);
        };
        return new LifecycleActivationManifest.Controller(
                kind, id, state, "NONE", runId,
                source, head,
                "/tmp/arch001-act-run-20260801");
    }
}
