package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.ContainerInfo;
import com.foggy.navigator.foundation.git.OpenHandsContainerManager;
import com.github.dockerjava.api.model.Container;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 容器管理服务
 */
@Service
@Slf4j
public class ContainerService {

    @Autowired
    private OpenHandsContainerManager containerManager;

    /**
     * 获取所有容器列表
     */
    public List<ContainerInfo> listContainers(String status) {
        log.info("查询容器列表: status={}", status);

        try {
            List<Container> containers = containerManager.listAllContainersWithDetails();

            return containers.stream()
                    .map(this::convertToContainerInfo)
                    .filter(info -> status == null || status.isEmpty() || status.equalsIgnoreCase(info.getStatus()))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("查询容器列表失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取单个容器信息
     */
    public ContainerInfo getContainer(String containerId) {
        log.info("查询容器: containerId={}", containerId);

        try {
            var containerStatus = containerManager.getContainerStatus(containerId);

            return ContainerInfo.builder()
                    .id(containerStatus.getId())
                    .name(containerStatus.getName())
                    .status(containerStatus.getStatus())
                    .running(containerStatus.isRunning())
                    .build();

        } catch (Exception e) {
            log.error("查询容器失败: containerId={}", containerId, e);
            return null;
        }
    }

    /**
     * 删除容器
     */
    public void deleteContainer(String containerId) {
        log.info("删除容器: containerId={}", containerId);
        containerManager.destroyContainer(containerId);
    }

    /**
     * 转换 Docker Container 到 ContainerInfo
     */
    private ContainerInfo convertToContainerInfo(Container container) {
        String status = container.getState();
        String name = container.getNames() != null && container.getNames().length > 0
                ? container.getNames()[0].replaceFirst("/", "")
                : "unknown";
        String image = container.getImage();

        LocalDateTime createdAt = container.getCreated() != null
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(container.getCreated()), ZoneId.systemDefault())
                : null;

        return ContainerInfo.builder()
                .id(container.getId())
                .name(name)
                .imageTag(image)
                .status(mapDockerStatusToApiStatus(status))
                .running("running".equalsIgnoreCase(status))
                .createdAt(createdAt)
                .build();
    }

    /**
     * 映射 Docker 状态到 API 状态
     */
    private String mapDockerStatusToApiStatus(String dockerStatus) {
        if (dockerStatus == null) {
            return "UNKNOWN";
        }

        switch (dockerStatus.toLowerCase()) {
            case "running":
                return "RUNNING";
            case "created":
                return "CREATING";
            case "exited":
            case "stopped":
                return "STOPPED";
            case "dead":
            case "removing":
                return "ERROR";
            default:
                return dockerStatus.toUpperCase();
        }
    }
}
