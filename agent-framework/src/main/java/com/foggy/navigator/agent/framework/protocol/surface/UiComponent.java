package com.foggy.navigator.agent.framework.protocol.surface;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * UI组件定义
 * 安全设计：仅支持预定义的组件类型（白名单机制）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiComponent {
    private String id;
    private ComponentType type;
    private String parentId;
    private Map<String, Object> props;
    private List<String> childIds;
    private String dataBinding;
    private Map<String, ActionConfig> actions;
}
