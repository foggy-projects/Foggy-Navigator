package com.foggy.navigator.workbench.fap.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foggy.agent.contract.access.v1alpha1.ExecutionSelection;
import com.foggy.agent.contract.access.v1alpha1.ScopeRequest;
import com.foggy.agent.contract.worker.v1alpha1.ProviderOptions;
import com.foggy.agent.contract.worker.v1alpha1.TaskInput;
import com.foggy.navigator.workbench.fap.config.WorkbenchFapProperties;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.ProviderOptionsForm;
import com.foggy.navigator.workbench.fap.web.WorkbenchFapException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Compiles browser-safe choices and frozen bindings into canonical SDK command inputs. */
@Component
public class WorkbenchFapCommandMapper {
    private final WorkbenchFapProperties properties;
    private final ObjectMapper mapper;

    public WorkbenchFapCommandMapper(
            WorkbenchFapProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public ExecutionSelection selection(
            String workerProfileRef,
            String workspaceRef,
            String modelConfigRef,
            boolean allowDefaultModelConfig) {
        return new ExecutionSelection(
                workerProfileRef,
                null,
                properties.getEnvironmentClass(),
                workspaceRef,
                null,
                modelConfigRef,
                allowDefaultModelConfig,
                null,
                null);
    }

    public TaskInput input(String prompt) {
        ObjectNode part = mapper.createObjectNode();
        part.put("type", "TEXT");
        part.put("text", prompt);
        return new TaskInput(List.of(part));
    }

    public ProviderOptions providerOptions(ProviderOptionsForm form) {
        if (form == null) return null;
        JsonNode payload = form.payload() == null ? mapper.createObjectNode() : form.payload();
        if (!payload.isObject()) {
            throw new IllegalArgumentException("providerOptions.payload must be an object");
        }
        return new ProviderOptions(
                required(form.namespace(), "providerOptions.namespace"),
                required(form.version(), "providerOptions.version"),
                payload.deepCopy());
    }

    public ScopeRequest persistedScope(String value) {
        try {
            JsonNode scope = mapper.readTree(required(value, "persisted effective scope"));
            return new ScopeRequest(scope, "DENY_ON_REDUCTION");
        } catch (JsonProcessingException error) {
            throw new WorkbenchFapException(
                    500,
                    "FAP_BINDING_SCOPE_INVALID",
                    "The immutable FAP conversation scope cannot be loaded",
                    false);
        }
    }

    public String jsonScope(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new WorkbenchFapException(
                    502,
                    "FAP_EFFECTIVE_SCOPE_MISSING",
                    "Access did not return an immutable effective scope",
                    false);
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new WorkbenchFapException(
                    500,
                    "FAP_EFFECTIVE_SCOPE_SERIALIZATION_FAILED",
                    "The effective scope could not be persisted",
                    false);
        }
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.strip();
    }
}
