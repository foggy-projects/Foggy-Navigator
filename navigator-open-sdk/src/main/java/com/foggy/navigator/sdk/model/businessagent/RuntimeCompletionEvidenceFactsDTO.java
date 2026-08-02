package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

/** Presence/digest-only completion evidence; this type never carries output. */
@JsonIgnoreProperties(ignoreUnknown = true)
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

    public Boolean getFinalOutputPresent() { return finalOutputPresent; }
    public void setFinalOutputPresent(Boolean value) { finalOutputPresent = value; }
    public Boolean getFinalOutputDurable() { return finalOutputDurable; }
    public void setFinalOutputDurable(Boolean value) { finalOutputDurable = value; }
    public String getFinalOutputDigest() { return finalOutputDigest; }
    public void setFinalOutputDigest(String value) { finalOutputDigest = value; }
    public OffsetDateTime getFinalOutputRecordedAt() { return finalOutputRecordedAt; }
    public void setFinalOutputRecordedAt(OffsetDateTime value) { finalOutputRecordedAt = value; }
    public Boolean getStructuredOutputPresent() { return structuredOutputPresent; }
    public void setStructuredOutputPresent(Boolean value) { structuredOutputPresent = value; }
    public String getStructuredOutputDigest() { return structuredOutputDigest; }
    public void setStructuredOutputDigest(String value) { structuredOutputDigest = value; }
    public Boolean getTerminalSignalPresent() { return terminalSignalPresent; }
    public void setTerminalSignalPresent(Boolean value) { terminalSignalPresent = value; }
    public String getTerminalSignalSource() { return terminalSignalSource; }
    public void setTerminalSignalSource(String value) { terminalSignalSource = value; }
    public OffsetDateTime getTerminalSignalRecordedAt() { return terminalSignalRecordedAt; }
    public void setTerminalSignalRecordedAt(OffsetDateTime value) { terminalSignalRecordedAt = value; }
    public String getTerminalErrorCode() { return terminalErrorCode; }
    public void setTerminalErrorCode(String value) { terminalErrorCode = value; }
    public Boolean getCompletionSignalPresent() { return completionSignalPresent; }
    public void setCompletionSignalPresent(Boolean value) { completionSignalPresent = value; }
    public String getCompletionSignalSource() { return completionSignalSource; }
    public void setCompletionSignalSource(String value) { completionSignalSource = value; }
    public OffsetDateTime getCompletionSignalRecordedAt() { return completionSignalRecordedAt; }
    public void setCompletionSignalRecordedAt(OffsetDateTime value) { completionSignalRecordedAt = value; }
    public Boolean getResultRecoverable() { return resultRecoverable; }
    public void setResultRecoverable(Boolean value) { resultRecoverable = value; }
}
