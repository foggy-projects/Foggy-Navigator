package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OpenTaskEvidenceDTO {

    private String taskId;

    private String agentId;

    private String contextId;

    private String status;

    private Boolean terminal;

    private String terminalStatus;

    private OpenTaskFinalAnswerDTO finalAnswer;

    private OpenTaskStructuredOutputDTO structuredOutput;

    private List<OpenTaskReportRefDTO> reportRefs;

    private List<OpenTaskArtifactRefDTO> artifactRefs;
}
