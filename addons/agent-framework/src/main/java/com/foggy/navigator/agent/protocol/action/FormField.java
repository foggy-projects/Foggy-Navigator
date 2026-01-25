package com.foggy.navigator.agent.protocol.action;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 表单字段
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormField {
    private String name;
    private String label;
    private String type;  // text / number / select / checkbox / ...
    private Object defaultValue;
    private boolean required;
    private List<Option> options;
}
