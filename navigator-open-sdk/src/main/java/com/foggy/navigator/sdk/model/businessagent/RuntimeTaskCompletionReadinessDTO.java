package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Formal typed completion-readiness graph. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeTaskCompletionReadinessDTO {
    private RuntimeTaskFactsDTO taskFacts;
    private RuntimeWorkerObservedFactsDTO workerObservedFacts;
    private RuntimeCompletionEvidenceFactsDTO completionEvidenceFacts;
    private RuntimeCompletionReconciliationAssessmentDTO reconciliationAssessment;
    private RuntimeAuditSideEffectsDTO auditSideEffects;

    public RuntimeTaskFactsDTO getTaskFacts() { return taskFacts; }
    public void setTaskFacts(RuntimeTaskFactsDTO value) { taskFacts = value; }
    public RuntimeWorkerObservedFactsDTO getWorkerObservedFacts() { return workerObservedFacts; }
    public void setWorkerObservedFacts(RuntimeWorkerObservedFactsDTO value) {
        workerObservedFacts = value;
    }
    public RuntimeCompletionEvidenceFactsDTO getCompletionEvidenceFacts() {
        return completionEvidenceFacts;
    }
    public void setCompletionEvidenceFacts(RuntimeCompletionEvidenceFactsDTO value) {
        completionEvidenceFacts = value;
    }
    public RuntimeCompletionReconciliationAssessmentDTO getReconciliationAssessment() {
        return reconciliationAssessment;
    }
    public void setReconciliationAssessment(RuntimeCompletionReconciliationAssessmentDTO value) {
        reconciliationAssessment = value;
    }
    public RuntimeAuditSideEffectsDTO getAuditSideEffects() { return auditSideEffects; }
    public void setAuditSideEffects(RuntimeAuditSideEffectsDTO value) { auditSideEffects = value; }
}
