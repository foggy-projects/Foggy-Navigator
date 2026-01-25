package com.foggy.navigator.agent.protocol.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * UI提示配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiHint {
    private boolean requireConfirmation;
    private String confirmationMessage;
    private String loadingMessage;
    private String icon;
    private String theme;  // info / warning / danger
}
