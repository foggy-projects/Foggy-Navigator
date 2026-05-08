package com.foggy.navigator.business.agent.service.worker;

public interface BusinessAgentWorkerTaskLauncher {

    String getWorkerBackend();

    BusinessAgentWorkerTaskLaunchResult launch(BusinessAgentWorkerTaskLaunchRequest request);
}
