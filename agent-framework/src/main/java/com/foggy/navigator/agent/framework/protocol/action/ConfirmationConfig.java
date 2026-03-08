package com.foggy.navigator.agent.framework.protocol.action;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 确认对话框配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationConfig {
    private String title;
    private String message;
    private String confirmText;
    private String cancelText;
    private String severity;  // info / warning / danger
}
