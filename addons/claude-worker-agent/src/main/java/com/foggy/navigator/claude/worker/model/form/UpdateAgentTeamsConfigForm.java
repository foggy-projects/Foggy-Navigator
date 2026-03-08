package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 更新 Agent Teams 配置表单（null 字段表示不修改）
 */
@Data
public class UpdateAgentTeamsConfigForm {
    private String name;
    private String config;
    private Boolean isDefault;
}
