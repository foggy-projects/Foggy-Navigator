package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class RuntimeCompletionReconciliationAssessmentDTO {
    private Boolean staleRegistrationSuspected;
    private Boolean workerProcessAbsent;
    private Boolean completionCandidate;
    private Boolean terminalEvidenceAuthoritative;
    private Boolean completionEvidenceAuthoritative;
    private Boolean completionReconciliationSupported;
    private Boolean terminationReconciliationSupported;
    private Boolean reconcileRequired;
    private String reconcileReason;
    private String recommendedAction;
    private String assessmentReason;
    private String assessmentSource;
    private String providerObservationErrorCode;
    private OffsetDateTime assessedAt;
}
