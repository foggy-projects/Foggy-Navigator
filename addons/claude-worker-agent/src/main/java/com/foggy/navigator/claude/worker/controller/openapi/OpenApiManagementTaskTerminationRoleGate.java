package com.foggy.navigator.claude.worker.controller.openapi;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.session.service.ScopedOpenApiManagementTaskTerminationCommandAdapter;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Proxied management-role boundary for the dual-lane OpenAPI Agent cancel route. */
@Service
public class OpenApiManagementTaskTerminationRoleGate {

    private final ScopedOpenApiManagementTaskTerminationCommandAdapter adapter;

    public OpenApiManagementTaskTerminationRoleGate(
            ScopedOpenApiManagementTaskTerminationCommandAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter must not be null");
    }

    @RequireAuth(roles = {"TENANT_ADMIN", "DEVELOPER"})
    public ManagementTerminationResult terminate(
            String pathAgentId,
            String taskId,
            @Nullable String suppliedClientRequestId) {
        // The method body runs only after AuthAspect, preserving auth/role priority.
        String clientRequestId = OpenApiAgentTaskTerminationFacade
                .canonicalClientRequestId(suppliedClientRequestId);
        ScopedOpenApiManagementTaskTerminationCommandAdapter.TerminationResult result =
                adapter.terminate(pathAgentId, taskId, clientRequestId);
        return new ManagementTerminationResult(
                clientRequestId,
                Objects.requireNonNull(
                        result, "management termination result must not be null"));
    }

    public record ManagementTerminationResult(
            String clientRequestId,
            ScopedOpenApiManagementTaskTerminationCommandAdapter.TerminationResult result) {
        public ManagementTerminationResult {
            Objects.requireNonNull(clientRequestId, "clientRequestId must not be null");
            Objects.requireNonNull(result, "result must not be null");
        }
    }
}
