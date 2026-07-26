package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RuntimeTaskCompletionReadinessDTO {
    private RuntimeTaskFactsDTO taskFacts;
    private RuntimeWorkerObservedFactsDTO workerObservedFacts;
    private RuntimeCompletionEvidenceFactsDTO completionEvidenceFacts;
    private RuntimeCompletionReconciliationAssessmentDTO reconciliationAssessment;
    private RuntimeAuditSideEffectsDTO auditSideEffects;
}
