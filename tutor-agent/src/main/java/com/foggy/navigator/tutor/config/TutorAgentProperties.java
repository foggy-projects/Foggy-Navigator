package com.foggy.navigator.tutor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tutor Agent 配置属性
 */
@Data
@ConfigurationProperties(prefix = "foggy.tutor-agent")
public class TutorAgentProperties {

    /**
     * 默认租户 ID
     */
    private String defaultTenantId = "default";

    /**
     * 是否在启动时初始化 Skill
     */
    private boolean initSkillsOnStartup = true;

    /**
     * Agent ID
     */
    private String agentId = "tutor-agent";

    /**
     * Skills 目录路径（支持 classpath: 或文件系统路径）
     */
    private String skillsDirectory = "classpath:skills/tutor";
}
