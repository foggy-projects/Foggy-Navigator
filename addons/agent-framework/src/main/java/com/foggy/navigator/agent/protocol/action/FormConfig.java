package com.foggy.navigator.agent.protocol.action;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 表单配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormConfig {
    private String title;
    private List<FormField> fields;
    private String submitText;
    private String cancelText;
}
