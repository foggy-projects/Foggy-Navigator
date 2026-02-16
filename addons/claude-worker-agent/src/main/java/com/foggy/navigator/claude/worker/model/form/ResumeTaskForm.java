package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 恢复任务表单
 */
@Data
public class ResumeTaskForm {
    private String workerId;
    private String claudeSessionId;
    private String prompt;
    private String cwd;
    private String directoryId;
    /** 复用已有 Navigator Session（per-conversation 模式） */
    private String sessionId;
    private String model;
    private Integer maxTurns;
    /** Agent Teams JSON (直接传入或由 directoryId 解析) */
    private String agentTeamsJson;
}
