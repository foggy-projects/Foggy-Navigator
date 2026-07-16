package com.foggy.navigator.session.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ErrorDiagnosticDTO {
    private String diagnosticId;
    private String diagnosticRef;
    private String taskId;
    private String providerType;
    private String runtimeType;
    private String errorCode;
    private String category;
    private String runtimePhase;
    private String safeMessage;
    private Boolean recoverable;
    private String providerStatus;
    private Integer httpStatus;
    private Integer retryCount;
    private String exceptionType;
    private String diagnosticText;
    private LocalDateTime occurredAt;
    private LocalDateTime expiresAt;
    private LocalDateTime shareExpiresAt;
    private Boolean publicSharingEnabled;
    private Integer defaultShareDays;
    private Integer maxShareDays;
}
