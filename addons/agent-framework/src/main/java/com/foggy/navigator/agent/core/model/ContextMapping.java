package com.foggy.navigator.agent.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文映射配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextMapping {
    private String key;
    private String source;  // userInput / systemConfig / sessionContext
    private String sourceKey;
}
