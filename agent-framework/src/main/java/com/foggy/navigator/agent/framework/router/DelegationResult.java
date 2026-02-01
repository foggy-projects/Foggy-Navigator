package com.foggy.navigator.agent.framework.router;

import com.foggy.navigator.agent.framework.protocol.route.RoutePayload;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分派结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelegationResult {
    private boolean success;
    private String newSessionId;
    private RoutePayload route;
    private String errorMessage;

    public static DelegationResult success(String newSessionId, RoutePayload route) {
        return DelegationResult.builder()
                .success(true)
                .newSessionId(newSessionId)
                .route(route)
                .build();
    }

    public static DelegationResult error(String message) {
        return DelegationResult.builder()
                .success(false)
                .errorMessage(message)
                .build();
    }
}
