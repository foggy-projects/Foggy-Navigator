package com.foggy.navigator.business.agent.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class WorkerGatewayResumeResponseDTO {

    private String status;

    @JsonProperty("suspend_id")
    private String suspendId;

    @JsonProperty("script_run_id")
    private String scriptRunId;

    @JsonProperty("resume_ref")
    private String resumeRef;
}
