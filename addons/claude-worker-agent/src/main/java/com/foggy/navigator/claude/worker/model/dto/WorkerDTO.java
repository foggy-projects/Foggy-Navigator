package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Worker 信息 DTO
 */
@Data
@Builder
public class WorkerDTO {
    private String workerId;
    private String name;
    private String baseUrl;
    private String authMode;
    private String status;
    private String hostname;
    private String workerVersion;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime createdAt;
    private String sshUsername;
    private Integer sshPort;
    private boolean sshPasswordConfigured;
    private String codeServerPublicUrl;
    private String codeServerInternalUrl;
    private boolean codeServerPasswordConfigured;
    private String codeServerFolderPrefix;
    /** Codex 配置 URL */
    private String codexBaseUrl;
    /** Codex 默认模型 */
    private String codexModel;
    /** Codex 认证令牌是否已配置 */
    private boolean codexAuthTokenConfigured;
}
