package com.foggy.navigator.common.form;

import lombok.Data;

/**
 * Agent 模型覆盖配置表单
 */
@Data
public class AgentModelOverrideForm {

    /**
     * Agent ID，如 "coding-agent"
     */
    private String agentId;

    /**
     * 引用的模型配置ID
     */
    private String modelConfigId;
}
