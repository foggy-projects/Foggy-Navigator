package com.foggy.navigator.gemini.worker.model.form;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 创建 Gemini 任务表单
 */
@Data
public class CreateGeminiTaskForm {
    private String agentId;
    private String workerId;
    private String prompt;
    private String cwd;
    private String directoryId;
    private String model;
    private Integer maxTurns;
    private List<Map<String, Object>> attachments;
    private String geminiSessionId;
    private String modelConfigId;
    private String sessionId;
}
