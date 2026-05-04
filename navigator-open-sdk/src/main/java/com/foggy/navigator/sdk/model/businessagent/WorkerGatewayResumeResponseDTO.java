package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkerGatewayResumeResponseDTO {
    private String status;
    @JsonProperty("suspend_id")
    private String suspendId;
    @JsonProperty("script_run_id")
    private String scriptRunId;
    @JsonProperty("resume_ref")
    private String resumeRef;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSuspendId() { return suspendId; }
    public void setSuspendId(String suspendId) { this.suspendId = suspendId; }
    public String getScriptRunId() { return scriptRunId; }
    public void setScriptRunId(String scriptRunId) { this.scriptRunId = scriptRunId; }
    public String getResumeRef() { return resumeRef; }
    public void setResumeRef(String resumeRef) { this.resumeRef = resumeRef; }
}
