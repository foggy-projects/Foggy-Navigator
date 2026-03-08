package com.foggy.navigator.coding.agent.api.service;

import com.foggy.navigator.coding.agent.api.model.ContainerInfo;
import com.foggy.navigator.coding.agent.git.OpenHandsInstanceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ContainerService {

    @Autowired
    private OpenHandsInstanceManager instanceManager;

    public List<ContainerInfo> listContainers(String status) {
        log.info("查询用户实例列表: status={}", status);

        try {
            Map<String, OpenHandsInstanceManager.UserInstance> instances = instanceManager.getAllInstances();

            return instances.values().stream()
                    .map(this::convertToContainerInfo)
                    .filter(info -> status == null || status.isEmpty() || status.equalsIgnoreCase(info.getStatus()))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("查询用户实例列表失败", e);
            return new ArrayList<>();
        }
    }

    public ContainerInfo getContainer(String containerId) {
        log.info("查询用户实例: containerId={}", containerId);

        try {
            Map<String, OpenHandsInstanceManager.UserInstance> instances = instanceManager.getAllInstances();
            for (OpenHandsInstanceManager.UserInstance instance : instances.values()) {
                if (instance.getContainerId().equals(containerId)) {
                    return convertToContainerInfo(instance);
                }
            }
            return null;

        } catch (Exception e) {
            log.error("查询用户实例失败: containerId={}", containerId, e);
            return null;
        }
    }

    public void deleteContainer(String containerId) {
        log.info("删除用户实例: containerId={}", containerId);
        Map<String, OpenHandsInstanceManager.UserInstance> instances = instanceManager.getAllInstances();
        for (OpenHandsInstanceManager.UserInstance instance : instances.values()) {
            if (instance.getContainerId().equals(containerId)) {
                instanceManager.shutdownUserInstance(instance.getUserId());
                return;
            }
        }
        log.warn("未找到对应的用户实例: containerId={}", containerId);
    }

    private ContainerInfo convertToContainerInfo(OpenHandsInstanceManager.UserInstance instance) {
        return ContainerInfo.builder()
                .id(instance.getContainerId())
                .name("openhands-user-" + instance.getUserId())
                .status(instance.isRunning() ? "RUNNING" : "STOPPED")
                .running(instance.isRunning())
                .createdAt(instance.getCreatedAt())
                .build();
    }
}
