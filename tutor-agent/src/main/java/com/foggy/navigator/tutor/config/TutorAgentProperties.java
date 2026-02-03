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
     * Coding Agent 服务基础 URL
     */
    private String codingAgentBaseUrl = "http://localhost:8112";

    /**
     * 默认租户 ID
     */
    private String defaultTenantId = "default";

    /**
     * HTTP 请求超时时间（毫秒）
     */
    private int connectTimeout = 5000;

    /**
     * HTTP 读取超时时间（毫秒）
     */
    private int readTimeout = 30000;

    /**
     * 是否在启动时初始化 Skill
     */
    private boolean initSkillsOnStartup = true;

    /**
     * Agent ID
     */
    private String agentId = "tutor-agent";
}
