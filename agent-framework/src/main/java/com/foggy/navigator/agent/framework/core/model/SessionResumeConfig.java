package com.foggy.navigator.agent.framework.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话恢复配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResumeConfig {
    private boolean enabled;
    private boolean checkOnStartup;
    private String reminderTemplate;
}
