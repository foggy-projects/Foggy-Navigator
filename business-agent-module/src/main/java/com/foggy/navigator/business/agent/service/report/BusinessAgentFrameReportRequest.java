package com.foggy.navigator.business.agent.service.report;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BusinessAgentFrameReportRequest {
    String tenantId;
    String clientAppId;
    String workerTaskId;
    String frameId;
    String reportRef;
    String contextId;
    String sessionId;
    String mode;
    Integer maxChars;
}
