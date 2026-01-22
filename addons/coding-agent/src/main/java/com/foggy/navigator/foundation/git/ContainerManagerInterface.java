package com.foggy.navigator.foundation.git;

import com.foggy.navigator.foundation.git.model.ContainerConfig;
import com.foggy.navigator.foundation.git.model.ContainerStatus;

public interface ContainerManagerInterface {
    String createContainer(String userId, String sessionId, ContainerConfig config);
    void destroyContainer(String containerId);
    ContainerStatus getContainerStatus(String containerId);
    boolean waitForContainerReady(String containerId, int timeoutSeconds);
    boolean startContainer(String containerId);
    boolean containerExists(String containerId);
    boolean isContainerRunning(String containerId);
}