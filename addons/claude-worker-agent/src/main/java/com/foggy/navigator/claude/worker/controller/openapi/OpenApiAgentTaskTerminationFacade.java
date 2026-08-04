package com.foggy.navigator.claude.worker.controller.openapi;

import com.foggy.navigator.auth.interceptor.OpenApiAgentCancelCredentialCensus;
import com.foggy.navigator.claude.worker.model.dto.OpenApiTaskDTO;
import com.foggy.navigator.claude.worker.service.RuntimeTaskClosureService;
import com.foggy.navigator.session.service.ScopedOpenApiManagementTaskTerminationCommandAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Single dual-lane composition boundary for OpenAPI Agent task termination. */
@Service
public final class OpenApiAgentTaskTerminationFacade {

    private static final String FAILURE = "OPEN_API_AGENT_CANCEL_FAILED";
    private static final String RESULT_IDENTITY_CONFLICT =
            "OPEN_API_AGENT_CANCEL_RESULT_IDENTITY_CONFLICT";
    private static final String AUTHENTICATION_REQUIRED = "未登录，请先登录";
    private static final String AUTHORIZATION_DENIED = "无权限访问此接口";
    private static final Set<String> SAFE_CODES = Set.of(
            FAILURE,
            "OPEN_API_AGENT_CANCEL_CLIENT_REQUEST_ID_INVALID",
            "AGENT_CANCEL_CLIENT_REQUEST_ID_INVALID",
            "RUNTIME_ACCESS_AUTHORITY_UNAVAILABLE",
            "RUNTIME_ACCESS_TOKEN_INVALID",
            "RUNTIME_ACCESS_TOKEN_REQUIRED",
            "RUNTIME_AGENT_RESOURCE_AUTHORITY_UNAVAILABLE",
            "RUNTIME_AGENT_TASK_AGENT_REQUIRED",
            "RUNTIME_AGENT_TASK_FORBIDDEN",
            "RUNTIME_AGENT_TASK_NOT_FOUND",
            "RUNTIME_AGENT_TASK_REQUIRED",
            "RUNTIME_AGENT_TASK_UPSTREAM_USER_REQUIRED",
            "RUNTIME_AGENT_TERMINAL_STATUS_INVALID",
            "RUNTIME_AGENT_TERMINAL_STATUS_UNSUPPORTED",
            "RUNTIME_AGENT_TERMINATION_AUTHORITY_UNAVAILABLE",
            "RUNTIME_AGENT_TERMINATION_NOT_READY",
            "RUNTIME_AGENT_TERMINATION_OBSERVATION_UNAVAILABLE",
            "RUNTIME_TASK_PROVIDER_UNSUPPORTED",
            "TERMINATION_AUTHORIZATION_AGENT_CONFLICT",
            "TERMINATION_AUTHORIZATION_BINDING_CONFLICT",
            "TERMINATION_AUTHORIZATION_IDENTITY_INCOMPLETE",
            "TERMINATION_AUTHORIZATION_IDENTITY_INVALID",
            "TERMINATION_AUTHORIZATION_UPSTREAM_CONFLICT");

    private final RuntimeTaskClosureService runtimeTaskClosureService;
    private final OpenApiManagementTaskTerminationRoleGate managementRoleGate;
    private final OpenApiTaskProjectionMapper taskProjectionMapper =
            new OpenApiTaskProjectionMapper();

    public OpenApiAgentTaskTerminationFacade(
            RuntimeTaskClosureService runtimeTaskClosureService,
            OpenApiManagementTaskTerminationRoleGate managementRoleGate) {
        this.runtimeTaskClosureService = Objects.requireNonNull(
                runtimeTaskClosureService, "runtimeTaskClosureService must not be null");
        this.managementRoleGate = Objects.requireNonNull(
                managementRoleGate, "managementRoleGate must not be null");
    }

    public OpenApiTaskDTO terminate(
            HttpServletRequest request,
            String pathAgentId,
            String taskId,
            @Nullable String suppliedClientRequestId) {
        OpenApiAgentCancelCredentialCensus.Decision stored =
                OpenApiAgentCancelCredentialCensus.requireStored(request);
        OpenApiAgentCancelCredentialCensus.Decision current =
                OpenApiAgentCancelCredentialCensus.inspect(request);
        if (current == null || !stored.equals(current)) {
            throw new SecurityException(
                    OpenApiAgentCancelCredentialCensus.CREDENTIAL_CENSUS_DRIFT);
        }
        if (stored.rejected()) {
            if (OpenApiAgentCancelCredentialCensus.CREDENTIAL_REQUIRED
                    .equals(stored.rejectionCode())
                    || OpenApiAgentCancelCredentialCensus.CREDENTIAL_MALFORMED
                    .equals(stored.rejectionCode())) {
                // Preserve the existing anonymous/invalid-credential 401 response contract.
                throw new SecurityException(AUTHENTICATION_REQUIRED);
            }
            throw new SecurityException(stored.rejectionCode());
        }

        String clientRequestId;
        String taskStatus;
        if (stored.runtimeAccess()) {
            clientRequestId = canonicalClientRequestId(suppliedClientRequestId);
            RuntimeTaskClosureService.AgentTerminationResult result;
            try {
                result = runtimeTaskClosureService.terminateAgentTaskWithRuntimeAccess(
                        requiredHeader(request, stored.appKeyHeader()),
                        requiredHeader(request, stored.accessTokenHeader()),
                        requiredHeader(request, stored.upstreamUserHeader()),
                        clientRequestId,
                        pathAgentId,
                        taskId);
            } catch (RuntimeException failure) {
                throw sanitized(failure);
            }
            requireRuntimeIdentity(result, clientRequestId, pathAgentId, taskId);
            taskStatus = result.taskStatus();
        } else if (stored.management()) {
            OpenApiManagementTaskTerminationRoleGate.ManagementTerminationResult
                    managementResult;
            try {
                managementResult = Objects.requireNonNull(
                        managementRoleGate.terminate(
                                pathAgentId, taskId, suppliedClientRequestId),
                        "management termination result must not be null");
            } catch (SecurityException authorizationFailure) {
                if (isAuthAspectFailure(authorizationFailure.getMessage())) {
                    // Preserve only the two fixed AuthAspect 401/403 messages.
                    throw authorizationFailure;
                }
                throw sanitized(authorizationFailure);
            } catch (RuntimeException failure) {
                throw sanitized(failure);
            }
            clientRequestId = managementResult.clientRequestId();
            ScopedOpenApiManagementTaskTerminationCommandAdapter.TerminationResult result =
                    managementResult.result();
            taskStatus = result.terminalStatus() == null
                    ? "CANCEL_REQUESTED" : result.terminalStatus();
        } else {
            throw new SecurityException(
                    OpenApiAgentCancelCredentialCensus.CREDENTIAL_CENSUS_MISSING);
        }

        return OpenApiTaskDTO.builder()
                .clientRequestId(clientRequestId)
                .taskId(taskId)
                .agentId(pathAgentId)
                .status(taskProjectionMapper.mapTaskStatus(taskStatus))
                .build();
    }

    static String canonicalClientRequestId(@Nullable String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            if (supplied.length() != 36) {
                throw new IllegalArgumentException();
            }
            String canonical = UUID.fromString(supplied).toString();
            if (!canonical.equals(supplied)) {
                throw new IllegalArgumentException();
            }
            return canonical;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "OPEN_API_AGENT_CANCEL_CLIENT_REQUEST_ID_INVALID");
        }
    }

    private static String requiredHeader(HttpServletRequest request, @Nullable String name) {
        if (name == null) {
            throw new SecurityException(
                    OpenApiAgentCancelCredentialCensus.CREDENTIAL_CENSUS_DRIFT);
        }
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw new SecurityException(
                    OpenApiAgentCancelCredentialCensus.CREDENTIAL_CENSUS_DRIFT);
        }
        return value;
    }

    private static void requireRuntimeIdentity(
            RuntimeTaskClosureService.AgentTerminationResult result,
            String clientRequestId,
            String pathAgentId,
            String taskId) {
        if (result == null
                || !clientRequestId.equals(result.clientRequestId())
                || !pathAgentId.equals(result.agentId())
                || !taskId.equals(result.taskId())) {
            throw new IllegalStateException(RESULT_IDENTITY_CONFLICT);
        }
    }

    private static RuntimeException sanitized(RuntimeException failure) {
        String safeCode = safeCode(failure != null ? failure.getMessage() : null);
        String effective = safeCode != null ? safeCode : FAILURE;
        if (failure instanceof SecurityException) {
            return new SecurityException(effective);
        }
        if (failure instanceof IllegalArgumentException) {
            return new IllegalArgumentException(effective);
        }
        return new IllegalStateException(effective);
    }

    @Nullable
    private static String safeCode(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String candidate = message.trim();
        return SAFE_CODES.contains(candidate) ? candidate : null;
    }

    private static boolean isAuthAspectFailure(@Nullable String message) {
        return AUTHENTICATION_REQUIRED.equals(message)
                || AUTHORIZATION_DENIED.equals(message);
    }
}
