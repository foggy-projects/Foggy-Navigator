package com.foggy.navigator.business.agent.service.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessFunctionExecutionReportFrame {
    private String frameId;
    private String parentFrameId;
    private String skillId;
    private String status;
    private String summary;
    private String executionReportRef;
    private Map<String, Object> executionReportDigest;
}
