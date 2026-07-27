package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/** Presence/digest-only completion evidence; never carries model output. */
@Data
@Builder
public class RuntimeCompletionEvidenceFactsDTO {
    private Boolean finalOutputPresent;
    private Boolean finalOutputDurable;
    private String finalOutputDigest;
    private OffsetDateTime finalOutputRecordedAt;
    private Boolean structuredOutputPresent;
    private String structuredOutputDigest;
    private Boolean terminalSignalPresent;
    private String terminalSignalSource;
    private OffsetDateTime terminalSignalRecordedAt;
    private String terminalErrorCode;
    private Boolean completionSignalPresent;
    private String completionSignalSource;
    private OffsetDateTime completionSignalRecordedAt;
    private Boolean resultRecoverable;
}
