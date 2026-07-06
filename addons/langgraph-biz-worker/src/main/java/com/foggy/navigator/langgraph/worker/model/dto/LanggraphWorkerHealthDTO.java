package com.foggy.navigator.langgraph.worker.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LanggraphWorkerHealthDTO {

    private String hostname;

    private String version;

    @JsonProperty("active_tasks")
    private Integer activeTasks;

    @JsonProperty("worker_name")
    private String workerName;

    private WorkerCapabilitiesDTO capabilities;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkerCapabilitiesDTO {

        @JsonProperty("agent_delegation")
        private AgentDelegationCapabilitiesDTO agentDelegation;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentDelegationCapabilitiesDTO {

        @JsonProperty("contract_version")
        private String contractVersion;

        @JsonProperty("max_agent_nesting_depth")
        private Integer maxAgentNestingDepth;

        @JsonProperty("root_agent_depth")
        private Integer rootAgentDepth;

        @JsonProperty("root_agent_delegation_allowed")
        private Boolean rootAgentDelegationAllowed;

        @JsonProperty("nested_agent_delegation_allowed")
        private Boolean nestedAgentDelegationAllowed;

        @JsonProperty("child_agent_inherits_parent_tools")
        private Boolean childAgentInheritsParentTools;

        @JsonProperty("explicit_nested_agent_authorization_required")
        private Boolean explicitNestedAgentAuthorizationRequired;

        @JsonProperty("nested_agent_authorization_gates")
        private List<String> nestedAgentAuthorizationGates;

        private Map<String, AgentDelegationToolCapabilityDTO> tools = new LinkedHashMap<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentDelegationToolCapabilityDTO {

        private Boolean supported;

        @JsonProperty("tool_name")
        private String toolName;

        private String mode;
    }
}
