package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

/**
 * Response DTO for Worker tool message reporting.
 */
@Data
public class WorkerGatewayToolMessageResponseDTO {
    private boolean accepted;
    private String message;

    public static WorkerGatewayToolMessageResponseDTO accepted() {
        WorkerGatewayToolMessageResponseDTO dto = new WorkerGatewayToolMessageResponseDTO();
        dto.setAccepted(true);
        dto.setMessage("Tool message accepted");
        return dto;
    }
}
