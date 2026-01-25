package com.foggy.navigator.agent.protocol.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 回调配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallbackConfig {
    private boolean notifyOnComplete;
    private boolean autoReturn;
    private String webhookUrl;
}
