package com.foggy.navigator.business.agent.service.report;

/**
 * Optional bridge for runtimes that maintain execution-frame reports outside
 * the business-agent module.
 */
public interface BusinessFunctionExecutionReportBridge {

    BusinessFunctionExecutionReportUpdate updateAfterBusinessFunctionResult(
            BusinessFunctionExecutionReportRequest request);

    static BusinessFunctionExecutionReportBridge noop() {
        return request -> null;
    }
}
