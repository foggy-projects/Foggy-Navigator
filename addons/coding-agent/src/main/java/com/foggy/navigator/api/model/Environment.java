package com.foggy.navigator.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 编辑环境
 * 每个环境对应一个 OpenHands 容器和独立的工作空间
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Environment {

    /**
     * 环境ID
     */
    private String id;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 项目ID
     */
    private String projectId;

    /**
     * Git 仓库地址
     */
    private String gitRepoUrl;

    /**
     * 分支名称
     */
    private String branchName;

    /**
     * OpenHands 容器ID
     */
    private String containerId;

    /**
     * 工作空间路径
     */
    private String workspacePath;

    /**
     * 命名空间（用于验证服务）
     */
    private String namespace;

    /**
     * 验证服务地址（可选，为空则使用默认配置）
     */
    private String validationServiceUrl;

    /**
     * 环境状态
     */
    private EnvironmentStatus status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 环境状态枚举
     */
    public enum EnvironmentStatus {
        CREATING,    // 创建中
        READY,       // 就绪
        BUSY,        // 忙碌中
        ERROR,       // 错误
        DESTROYED    // 已销毁
    }
}
