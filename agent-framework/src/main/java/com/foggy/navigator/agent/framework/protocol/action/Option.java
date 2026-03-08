package com.foggy.navigator.agent.framework.protocol.action;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 选项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Option {
    private String value;
    private String label;
    private String description;
    private String icon;
}
