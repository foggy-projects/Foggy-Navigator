package com.foggy.navigator.business.agent.service.report;

import java.util.Map;

public interface BusinessAgentFrameReportReader {

    boolean supportsWorkerTask(String workerTaskId);

    Map<String, Object> readFrameReport(BusinessAgentFrameReportRequest request);
}
