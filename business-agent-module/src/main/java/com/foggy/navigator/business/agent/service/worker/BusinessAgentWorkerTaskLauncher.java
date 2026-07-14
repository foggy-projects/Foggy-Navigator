package com.foggy.navigator.business.agent.service.worker;

public interface BusinessAgentWorkerTaskLauncher {

    String getWorkerBackend();

    /**
     * Resolves the exact physical Worker for a task without performing any
     * remote network call. The caller persists this result in the task token
     * before invoking {@link #launch(BusinessAgentWorkerTaskLaunchRequest)}.
     */
    String resolveWorkerId(BusinessAgentWorkerTaskLaunchRequest request);

    BusinessAgentWorkerTaskLaunchResult launch(BusinessAgentWorkerTaskLaunchRequest request);
}
