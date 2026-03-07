package com.foggy.navigator.codex.worker.model.form;

import lombok.Data;

/**
 * 创建 Codex 任务表单
 */
@Data
public class CreateCodexTaskForm {
    private String workerId;
    private String prompt;
    private String cwd;
    private String directoryId;
    private String model;
    private Integer maxTurns;
    /** Codex SDK thread ID（非空则恢复已有会话） */
    private String codexThreadId;
    /** 平台 LLM 模型配置 ID，用于从平台配置中获取 apiKey */
    private String modelConfigId;
}
