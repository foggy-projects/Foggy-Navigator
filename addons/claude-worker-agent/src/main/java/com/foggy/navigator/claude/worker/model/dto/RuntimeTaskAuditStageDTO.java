package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RuntimeTaskAuditStageDTO {
    private String stage;
    private String status;
    private String sanitizedErrorCode;
    private LocalDateTime occurredAt;
}
