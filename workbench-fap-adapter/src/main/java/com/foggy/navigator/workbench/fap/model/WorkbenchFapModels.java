package com.foggy.navigator.workbench.fap.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.foggy.agent.contract.access.v1alpha1.ScopeReduction;
import java.time.LocalDateTime;
import java.util.List;

/** Browser-safe product DTOs. No Access grant, Runtime plan, route, credential, or ticket is exposed. */
public final class WorkbenchFapModels {
    private WorkbenchFapModels() {}

    public record Availability(
            boolean packaged, boolean enabled, boolean eligible, String executionLane) {}

    public record ProviderOptionsForm(String namespace, String version, JsonNode payload) {}

    public record StartConversationForm(
            String requestId,
            String title,
            String workerProfileRef,
            String workspaceRef,
            String modelConfigRef,
            Boolean allowDefaultModelConfig,
            String prompt,
            ProviderOptionsForm providerOptions) {}

    public record ContinueConversationForm(
            String requestId, String prompt, ProviderOptionsForm providerOptions) {}

    public record OperationForm(String requestId, String reasonCode, String message) {}

    public record ConversationView(
            String conversationId,
            String executionLane,
            String bindingStatus,
            String title,
            String workerProfileRef,
            String workspaceRef,
            String modelConfigRef,
            boolean allowDefaultModelConfig,
            String executionId,
            String currentTaskId,
            Long executionRevision,
            Long taskRevision,
            String taskType,
            String coordinationState,
            String displayState,
            Boolean definitiveTerminal,
            String terminalKind,
            String lastErrorCode,
            LocalDateTime updatedAt,
            List<ScopeReduction> scopeReductions) {
        public ConversationView {
            scopeReductions = scopeReductions == null ? List.of() : List.copyOf(scopeReductions);
        }
    }

    public record WorkbenchFapError(String code, String message, boolean retryable) {}
}
