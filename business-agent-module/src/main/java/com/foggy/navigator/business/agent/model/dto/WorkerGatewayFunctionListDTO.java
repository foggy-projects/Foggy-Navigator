package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class WorkerGatewayFunctionListDTO {
    private List<WorkerGatewayFunctionSummaryDTO> functions;
}
