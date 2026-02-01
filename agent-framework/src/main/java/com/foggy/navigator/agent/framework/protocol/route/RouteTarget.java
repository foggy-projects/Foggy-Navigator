package com.foggy.navigator.agent.framework.protocol.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 路由目标
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteTarget {
    private String agentId;
    private String agentName;
    private String sessionId;
    private String url;
    private Map<String, String> params;
}
