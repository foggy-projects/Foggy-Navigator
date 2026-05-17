package com.foggy.navigator.business.agent.service.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessFunctionExecutionReportUpdate {
    private String executionReportRef;
    private Map<String, Object> executionReportDigest;
    private String functionFrameId;
    private String functionExecutionReportRef;
    private Map<String, Object> functionExecutionReportDigest;
    private String rootFrameId;
    private String rootExecutionReportRef;
    private Map<String, Object> rootExecutionReportDigest;
    private String childExecutionReportRef;
    private Map<String, Object> childExecutionReportDigest;
    private List<BusinessFunctionExecutionReportFrame> closedSkillFrames;
}
