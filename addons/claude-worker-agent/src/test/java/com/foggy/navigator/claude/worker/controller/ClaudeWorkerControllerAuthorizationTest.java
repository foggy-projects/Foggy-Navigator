package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeWorkerControllerAuthorizationTest {

    @Test
    void manualPidKillRequiresTenantAdministratorRole() throws Exception {
        Method method = ClaudeWorkerController.class.getMethod(
                "killCliProcess", String.class, int.class, Map.class);

        RequireAuth authorization = method.getAnnotation(RequireAuth.class);

        assertNotNull(authorization);
        assertTrue(Arrays.asList(authorization.roles()).contains("TENANT_ADMIN"));
    }
}
