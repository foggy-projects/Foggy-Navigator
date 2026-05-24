package com.foggy.navigator.business.agent.model.form;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateBusinessAgentTaskForm {
    private String clientAppId;
    private String sessionId;
    private String contextId;
    private String upstreamUserId;
    @JsonAlias({"agent_id"})
    private String agentId;
    private String skillId;
    @JsonProperty("skill_name")
    @JsonAlias({"skillName"})
    private String skillName;
    private String workerPoolId;
    private String requestedModelConfigId;
    @JsonAlias({"workingDirectoryId", "working_directory_id", "directory_id"})
    private String directoryId;
    private String resumeFromTaskId;
    private String clientContextJson;
    @JsonAlias({"workDir", "working_dir", "workingDirectory", "working_directory"})
    private String workdir;
    @JsonProperty("allowed_dirs")
    @JsonAlias({"allowedDirs", "allowed_directories", "allowedDirectories"})
    private List<String> allowedDirs;
    @JsonProperty("allowed_tools")
    @JsonAlias({"allowedTools", "authorized_tools", "authorizedTools", "tool_allowlist", "toolAllowlist"})
    private List<String> allowedTools;
}
