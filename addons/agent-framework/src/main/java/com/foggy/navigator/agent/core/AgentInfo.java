package com.foggy.navigator.agent.core;

import com.foggy.navigator.agent.core.model.AgentConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent运行时信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentInfo {
    private String id;
    private String name;
    private String type;
    private String description;
    private List<String> capabilities;
    private AgentConfig config;
    private AgentStatus status;
    private LocalDateTime registeredAt;
    private LocalDateTime lastActiveAt;

    public static AgentInfo fromConfig(AgentConfig config) {
        return AgentInfo.builder()
                .id(config.getId())
                .name(config.getName())
                .type(config.getType())
                .description(config.getDescription())
                .capabilities(config.getCapabilities())
                .config(config)
                .status(AgentStatus.REGISTERED)
                .registeredAt(LocalDateTime.now())
                .build();
    }
}
