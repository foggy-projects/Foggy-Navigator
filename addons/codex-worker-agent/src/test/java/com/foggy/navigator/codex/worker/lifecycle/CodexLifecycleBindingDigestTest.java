package com.foggy.navigator.codex.worker.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.termination.TerminationOperationCapability;
import org.junit.jupiter.api.Test;

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
}
