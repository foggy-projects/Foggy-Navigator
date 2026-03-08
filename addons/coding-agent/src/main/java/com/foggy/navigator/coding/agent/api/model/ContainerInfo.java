package com.foggy.navigator.coding.agent.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 容器信息 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerInfo {

    /**
     * 容器ID
     */
    private String id;

    /**
     * 容器名称
     */
    private String name;

    /**
     * 镜像标签
     */
    private String imageTag;

    /**
     * 状态：CREATING, RUNNING, STOPPED, ERROR
     */
    private String status;

    /**
     * 是否运行中
     */
    private Boolean running;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 停止时间
     */
    private LocalDateTime stoppedAt;

    /**
     * 错误信息
     */
    private String errorMessage;
}
