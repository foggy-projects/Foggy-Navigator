package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpstreamBootstrapRequestCreatedDTO {
    private String requestCode;
    private String requestCodeSuffix;
    private String claimToken;
    private String status;
    private LocalDateTime requestExpiresAt;
}
