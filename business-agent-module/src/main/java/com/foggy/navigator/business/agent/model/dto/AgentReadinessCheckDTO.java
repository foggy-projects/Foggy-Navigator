package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

@Data
public class AgentReadinessCheckDTO {
    private String code;
    private String status;
    private String message;

    public static AgentReadinessCheckDTO ok(String code) {
        AgentReadinessCheckDTO dto = new AgentReadinessCheckDTO();
        dto.setCode(code);
        dto.setStatus("OK");
        return dto;
    }

    public static AgentReadinessCheckDTO fail(String code, String message) {
        AgentReadinessCheckDTO dto = new AgentReadinessCheckDTO();
        dto.setCode(code);
        dto.setStatus("FAIL");
        dto.setMessage(message);
        return dto;
    }
}
