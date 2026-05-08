package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class CreatedBusinessAgentTaskDTO extends BusinessAgentTaskDTO {
    private String taskScopedToken;

    public String getTaskScopedToken() { return taskScopedToken; }
    public void setTaskScopedToken(String taskScopedToken) { this.taskScopedToken = taskScopedToken; }
}
