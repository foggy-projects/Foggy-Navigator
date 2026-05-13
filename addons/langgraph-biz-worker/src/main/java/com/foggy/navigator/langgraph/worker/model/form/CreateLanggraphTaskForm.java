package com.foggy.navigator.langgraph.worker.model.form;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CreateLanggraphTaskForm {
    private String agentId;
    private String workerId;
    private String prompt;
    private String directoryId;
    private String cwd;
    private String model;
    private String modelConfigId;
    private String contextId;
    private String sessionId;
    /** Business context passed to Python Worker (serialized as JSON in request) */
    private Map<String, Object> context;
    /** Hidden runtime context for Worker internals. Never include this in LLM-visible prompts. */
    private Map<String, Object> runtimeContext;
    /** 上游已上传附件元数据和 URL */
    private List<Map<String, Object>> attachments;
}
