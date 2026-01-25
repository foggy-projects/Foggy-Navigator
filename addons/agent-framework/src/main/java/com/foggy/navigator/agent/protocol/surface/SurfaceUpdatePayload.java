package com.foggy.navigator.agent.protocol.surface;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * UI结构更新载荷
 * 采用扁平化组件列表 + ID引用（参考A2UI的adjacency list模型）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SurfaceUpdatePayload {
    private String surfaceId;
    private String rootComponentId;
    private List<UiComponent> components;
}
