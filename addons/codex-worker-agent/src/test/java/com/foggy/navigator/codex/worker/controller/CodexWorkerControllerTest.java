package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexWorkerControllerTest {

    @Test
    void resolvesOnlyTheFreshTaskBoundProcessIdentity() {
        Map<String, Object> snapshot = Map.of("processes", List.of(
                Map.of("pid", 321, "foggy_task_id", "task-1",
                        "process_identity", "codex-cli:321:2026-07-16T03:40:13.655Z"),
                Map.of("pid", 321, "foggy_task_id", "other-task",
                        "process_identity", "codex-cli:321:2026-07-16T03:40:13.655Z")
        ));

        assertEquals("codex-cli:321:2026-07-16T03:40:13.655Z",
                CodexWorkerController.processIdentityForPidTask(snapshot, 321, "task-1"));
    }

    @Test
    void rejectsPidTaskBindingWithoutOpaqueProcessIdentity() {
        Map<String, Object> snapshot = Map.of("processes", List.of(
                Map.of("pid", 321, "foggy_task_id", "task-1")
        ));

        assertNull(CodexWorkerController.processIdentityForPidTask(snapshot, 321, "task-1"));
    }

    @Test
    void manualPidKillRequiresTenantAdministratorRole() throws Exception {
        Method method = CodexWorkerController.class.getMethod(
                "killCliProcess", String.class, int.class, Map.class);

        RequireAuth authorization = method.getAnnotation(RequireAuth.class);

        assertNotNull(authorization);
        assertTrue(Arrays.asList(authorization.roles()).contains("TENANT_ADMIN"));
    }

    @Test
    void exposesStableLocalTerminationCodesWithoutLeakingArbitraryMessages() {
        assertEquals("TERMINATION_TASK_ACCESS_DENIED",
                CodexWorkerController.safeWorkerErrorCode(
                        new IllegalArgumentException("TERMINATION_TASK_ACCESS_DENIED")));
        assertEquals("TERMINATION_OPERATION_PENDING",
                CodexWorkerController.safeWorkerErrorCode(
                        new IllegalStateException("TERMINATION_OPERATION_PENDING")));
        assertEquals("CODEX_WORKER_HTTP_409",
                CodexWorkerController.safeWorkerErrorCode(WebClientResponseException.create(
                        409, "Conflict", null, null, null)));
        assertEquals("CODEX_WORKER_REQUEST_UNCONFIRMED",
                CodexWorkerController.safeWorkerErrorCode(
                        new IllegalArgumentException("sensitive internal detail")));
    }
}
