package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

/** Conservative read-only completion/cleanup reconciliation assessment. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeCompletionReconciliationAssessmentDTO {
    private Boolean staleRegistrationSuspected;
    private Boolean workerProcessAbsent;
    private Boolean completionCandidate;
    private Boolean terminalEvidenceAuthoritative;
    private Boolean completionEvidenceAuthoritative;
    private Boolean completionReconciliationSupported;
    private Boolean terminationReconciliationSupported;
    private Boolean reconcileRequired;
    private String reconcileReason = "UNKNOWN";
    private String recommendedAction = "UNKNOWN";
    private String assessmentReason = "UNKNOWN";
    private String assessmentSource = "UNKNOWN";
    private String providerObservationErrorCode;
    private OffsetDateTime assessedAt;

    public Boolean getStaleRegistrationSuspected() { return staleRegistrationSuspected; }
    public void setStaleRegistrationSuspected(Boolean value) { staleRegistrationSuspected = value; }
    public Boolean getWorkerProcessAbsent() { return workerProcessAbsent; }
    public void setWorkerProcessAbsent(Boolean value) { workerProcessAbsent = value; }
    public Boolean getCompletionCandidate() { return completionCandidate; }
    public void setCompletionCandidate(Boolean value) { completionCandidate = value; }
    public Boolean getTerminalEvidenceAuthoritative() { return terminalEvidenceAuthoritative; }
    public void setTerminalEvidenceAuthoritative(Boolean value) { terminalEvidenceAuthoritative = value; }
    public Boolean getCompletionEvidenceAuthoritative() { return completionEvidenceAuthoritative; }
    public void setCompletionEvidenceAuthoritative(Boolean value) { completionEvidenceAuthoritative = value; }
    public Boolean getCompletionReconciliationSupported() { return completionReconciliationSupported; }
    public void setCompletionReconciliationSupported(Boolean value) { completionReconciliationSupported = value; }
    public Boolean getTerminationReconciliationSupported() { return terminationReconciliationSupported; }
    public void setTerminationReconciliationSupported(Boolean value) { terminationReconciliationSupported = value; }
    public Boolean getReconcileRequired() { return reconcileRequired; }
    public void setReconcileRequired(Boolean value) { reconcileRequired = value; }
    public String getReconcileReason() { return normalized(reconcileReason); }
    public void setReconcileReason(String value) { reconcileReason = normalized(value); }
    public String getRecommendedAction() { return normalized(recommendedAction); }
    public void setRecommendedAction(String value) { recommendedAction = normalized(value); }
    public String getAssessmentReason() { return normalized(assessmentReason); }
    public void setAssessmentReason(String value) { assessmentReason = normalized(value); }
    public String getAssessmentSource() { return normalized(assessmentSource); }
    public void setAssessmentSource(String value) { assessmentSource = normalized(value); }
    public String getProviderObservationErrorCode() { return providerObservationErrorCode; }
    public void setProviderObservationErrorCode(String value) { providerObservationErrorCode = value; }
    public OffsetDateTime getAssessedAt() { return assessedAt; }
    public void setAssessedAt(OffsetDateTime value) { assessedAt = value; }

    private String normalized(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
