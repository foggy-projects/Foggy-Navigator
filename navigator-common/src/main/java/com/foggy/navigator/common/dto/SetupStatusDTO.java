package com.foggy.navigator.common.dto;

import lombok.Data;

/**
 * 系统初始化状态 DTO
 */
@Data
public class SetupStatusDTO {

    /**
     * 是否已配置 Git 提供者
     */
    private boolean gitConfigured;

    /**
     * 是否已配置 LLM 模型
     */
    private boolean llmConfigured;

    /**
     * 是否已配置 API 凭证
     */
    private boolean credentialConfigured;

    /**
     * 初始化是否完成（git + llm 都配好）
     */
    private boolean setupComplete;
}
