package com.foggy.navigator.agent.protocol.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 路由协议载荷
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutePayload {
    private RouteAction action;
    private RouteMode mode;
    private RouteTarget target;
    private ContextTransfer context;
    private UiHint uiHint;
    private CallbackConfig callback;
}
