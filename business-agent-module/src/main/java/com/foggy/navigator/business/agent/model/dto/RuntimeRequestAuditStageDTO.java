package com.foggy.navigator.business.agent.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class RuntimeRequestAuditStageDTO {
    private String stage;
    private String status;
    private String sanitizedErrorCode;
    private Instant occurredAt;
}
