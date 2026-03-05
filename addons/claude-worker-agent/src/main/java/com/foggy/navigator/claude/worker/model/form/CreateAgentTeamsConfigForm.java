package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 创建 Agent Teams 配置表单
 */
@Data
public class CreateAgentTeamsConfigForm {
    /** 配置名称 (必填) */
    private String name;
    /** Agent Teams JSON 内容 (必填) */
    private String config;
    /** 是否设为默认 */
    private Boolean isDefault;
}
