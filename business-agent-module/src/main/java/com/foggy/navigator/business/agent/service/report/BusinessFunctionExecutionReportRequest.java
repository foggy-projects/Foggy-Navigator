package com.foggy.navigator.business.agent.service.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessFunctionExecutionReportRequest {
    private String workerTaskId;
    private String workerSessionId;
    private String suspendId;
    private String functionId;
    private String version;
    private boolean success;
    private String status;
    private String executionStatus;
    private String content;
    private String errorMessage;
    private String businessTaskId;
    private String businessSessionId;
    private String adapterOutputJson;
    private String outputCode;
    private boolean hasOutputData;
}
