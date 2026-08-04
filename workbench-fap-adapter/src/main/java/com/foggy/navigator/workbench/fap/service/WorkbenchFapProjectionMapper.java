package com.foggy.navigator.workbench.fap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foggy.agent.contract.access.v1alpha1.ScopeReduction;
import com.foggy.agent.contract.runtime.v1alpha1.CreateExecutionAccepted;
import com.foggy.agent.contract.runtime.v1alpha1.CreateTaskAccepted;
import com.foggy.agent.contract.runtime.v1alpha1.ExecutionSnapshot;
import com.foggy.agent.contract.runtime.v1alpha1.TaskSnapshot;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.ConversationView;
import com.foggy.navigator.workbench.fap.persistence.WorkbenchFapConversationBindingEntity;
import java.util.List;
import org.springframework.stereotype.Component;

/** Produces browser-safe Workbench projections without upgrading Runtime/Worker facts. */
@Component
public class WorkbenchFapProjectionMapper {

    public ConversationView liveView(
            WorkbenchFapConversationBindingEntity binding,
            ExecutionSnapshot execution,
            TaskSnapshot task,
            List<ScopeReduction> reductions) {
        return view(
                binding,
                execution.revision(),
                task.revision(),
                task.taskType(),
                task.lifecycle(),
                reductions);
    }

    public ConversationView acceptedView(
            WorkbenchFapConversationBindingEntity binding,
            CreateExecutionAccepted accepted,
            List<ScopeReduction> reductions) {
        return view(
                binding,
                accepted.executionRevision(),
                1L,
                "START",
                accepted.lifecycle(),
                reductions);
    }

    public ConversationView acceptedView(
            WorkbenchFapConversationBindingEntity binding,
            CreateTaskAccepted accepted,
            List<ScopeReduction> reductions) {
        return view(
                binding,
                null,
                accepted.taskRevision(),
                "CONTINUE",
                accepted.lifecycle(),
                reductions);
    }

    public ConversationView localView(
            WorkbenchFapConversationBindingEntity binding,
            List<ScopeReduction> reductions) {
        return view(binding, null, null, null, null, reductions);
    }

    public JsonNode sanitizeEvents(JsonNode value) {
        JsonNode result = value.deepCopy();
        if (result instanceof ObjectNode page && page.path("events") instanceof ArrayNode events) {
            for (JsonNode event : events) {
                if (event instanceof ObjectNode envelope) {
                    envelope.remove(List.of("workerConversationId", "operationTicketId"));
                    sanitizeResourceRefs(envelope.path("resourceRefs"));
                }
            }
        }
        return result;
    }

    public JsonNode sanitizeResources(JsonNode value) {
        JsonNode result = value.deepCopy();
        if (result instanceof ObjectNode page) {
            // Canonical Worker ResourcePage uses `items`; retain `resources` only as an
            // additive compatibility key. Both paths must stay browser-safe.
            sanitizeResourceRefs(page.path("items"));
            sanitizeResourceRefs(page.path("resources"));
        }
        return result;
    }

    public JsonNode sanitizeRecovery(JsonNode value) {
        JsonNode result = value.deepCopy();
        if (result instanceof ObjectNode object) {
            object.remove(List.of(
                    "workerId",
                    "workerStateStoreId",
                    "workerProcessInstanceId",
                    "workerConversationId",
                    "activePrimaryTicketRef",
                    "lastTerminalReceiptRef"));
            if (object.path("resume") instanceof ObjectNode resume) {
                resume.remove("resumePointRef");
            }
            sanitizeResourceRefs(object.path("resourceRefs"));
        }
        return result;
    }

    private ConversationView view(
            WorkbenchFapConversationBindingEntity binding,
            Long executionRevision,
            Long taskRevision,
            String taskType,
            JsonNode lifecycle,
            List<ScopeReduction> reductions) {
        return new ConversationView(
                binding.getConversationId(),
                binding.getExecutionLane(),
                binding.getBindingStatus().name(),
                binding.getDisplayTitle(),
                binding.getWorkerProfileRef(),
                binding.getWorkspaceRef(),
                binding.getModelConfigRef(),
                binding.isAllowDefaultModelConfig(),
                binding.getExecutionId(),
                binding.getCurrentTaskId(),
                executionRevision,
                taskRevision,
                taskType,
                lifecycle == null ? null : text(lifecycle, "coordinationState"),
                lifecycle == null ? null : text(lifecycle, "displayState"),
                lifecycle == null || !lifecycle.has("definitiveTerminal")
                        ? null
                        : lifecycle.path("definitiveTerminal").asBoolean(),
                lifecycle == null ? null : text(lifecycle, "terminalKind"),
                binding.getLastErrorCode(),
                binding.getUpdatedAt(),
                reductions);
    }

    private void sanitizeResourceRefs(JsonNode value) {
        if (!(value instanceof ArrayNode resources)) return;
        for (JsonNode resource : resources) {
            if (resource instanceof ObjectNode item) item.remove("producerTicketId");
        }
    }

    private String text(JsonNode value, String field) {
        String result = value.path(field).asText();
        return result == null || result.isBlank() ? null : result;
    }
}
