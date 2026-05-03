package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

@Data
public class WorkerGatewayInvokeResponseDTO {
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_ADAPTER_NOT_IMPLEMENTED = "ADAPTER_NOT_IMPLEMENTED";

    private String functionId;
    private String version;
    private String status;
    private Boolean approvalRequired;
    private String suspendId;
    private String message;
    private String outputJson;
}
