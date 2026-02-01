package com.foggy.navigator.agent.framework.protocol.action;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 选择配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelectionConfig {
    private String title;
    private String message;
    private List<Option> options;
    private boolean allowMultiple;
}
