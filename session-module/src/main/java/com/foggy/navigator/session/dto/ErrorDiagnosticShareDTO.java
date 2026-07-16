package com.foggy.navigator.session.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ErrorDiagnosticShareDTO {
    private String shareId;
    private String diagnosticId;
    private String shareUrl;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime lastAccessAt;
    private Long accessCount;
}
