package com.foggy.navigator.codex.worker.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.entity.TerminationOperationEntity;
import com.foggy.navigator.common.termination.TerminationOperationCapability;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodexLifecycleBindingDigestTest {

    @Test
    void matchesNodeLifecycleV1CanonicalBindingVector() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("ownership_mode", "ENFORCED");
        context.put("command_kind", "TERMINATION_CANCEL");
        context.put("navigator_task_id", "task-1");
        context.put("dispatch_id", "dispatch-1");
        context.put("termination_operation_id", "op-1");

        String digest = new CodexLifecycleBindingDigest(
                new ObjectMapper()).termination(
                context, "provider-1",
                new TerminationOperationCapability(
                        "encoded-capability", "unused-signature"));

        assertThat(digest).isEqualTo(
                "mYvXaDe8OF0FAfBZ1LljYhDgWRdpUxQy2dwgJ66QGco");
    }

    @Test
    void stableCapabilitySurvivesMySqlDatetime6RoundTrip() {
        LocalDateTime requestedAt =
                LocalDateTime.of(2026, 7, 31, 15, 42, 11, 987_654_321);
        TerminationOperationEntity inserted = operation(requestedAt);
        TerminationOperationEntity reloaded = operation(
                requestedAt.withNano(987_654_000));

        TerminationOperationCapability before =
                TerminationOperationCapability.issueStable(
                        inserted, "worker-token");
        TerminationOperationCapability after =
                TerminationOperationCapability.issueStable(
                        reloaded, "worker-token");

        assertThat(after).isEqualTo(before);
    }

    @Test
    void activationTaskBindingMatchesRealNodeCanonicalizer() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("prompt", "static");
        request.put("cwd", "/tmp/activation");
        request.put("model", "gpt-5.6-sol");
        request.put("network_access_enabled", false);
        request.put("web_search_mode", "disabled");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("ownership_mode", "ENFORCED");
        context.put("command_kind", "TASK_CREATE");
        context.put("navigator_task_id", "task-activation");
        context.put("dispatch_id", "dispatch-activation");
        context.put("termination_operation_id", null);

        String digest = new CodexLifecycleBindingDigest(
                new ObjectMapper()).task(request, context);

        assertThat(digest).isEqualTo(
                "7l4_e49NJxXbnQDU97GNNpjceqNAhBalyyFg-mwbhK8");
    }

    private TerminationOperationEntity operation(LocalDateTime requestedAt) {
        TerminationOperationEntity operation =
                new TerminationOperationEntity();
        operation.setSchemaVersion(1);
        operation.setOperationId("rt_stable");
        operation.setProviderTaskId("provider-1");
        operation.setWorkerId("worker-1");
        operation.setKind("REMOTE_CANCEL");
        operation.setOrigin("UPSTREAM_USER");
        operation.setActorId("owner-1");
        operation.setActorType("RUNTIME_CLIENT");
        operation.setAuthorizationDecisionId(
                "runtime-closure:request-1");
        operation.setReasonCode(
                "operator-stuck-task-termination");
        operation.setCorrelationId(
                "runtime-task-terminate:request-1");
        operation.setRequestedAt(requestedAt);
        operation.setExpiresAt(requestedAt.plusMinutes(5));
        return operation;
    }
}
