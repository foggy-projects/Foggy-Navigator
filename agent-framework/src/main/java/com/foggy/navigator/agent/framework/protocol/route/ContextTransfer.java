package com.foggy.navigator.agent.framework.protocol.route;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 上下文传递配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextTransfer {
    private List<String> messageIds;
    private Map<String, Object> variables;
    private String summary;
    private boolean preserveHistory;
}
