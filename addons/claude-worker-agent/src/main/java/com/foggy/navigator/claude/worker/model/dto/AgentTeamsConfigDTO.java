package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent Teams 配置 DTO
 */
@Data
@Builder
public class AgentTeamsConfigDTO {
    private String configId;
    private String directoryId;
    private String name;
    private String config;
    private Boolean isDefault;
    /** 从 config JSON 解析出的 agent 名称列表（便于前端显示） */
    private List<String> agentNames;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
