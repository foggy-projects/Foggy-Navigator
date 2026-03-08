package com.foggy.navigator.agent.framework.protocol.surface;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 组件动作配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionConfig {
    private String actionType;  // route / submit / custom
    private Map<String, Object> params;
}
