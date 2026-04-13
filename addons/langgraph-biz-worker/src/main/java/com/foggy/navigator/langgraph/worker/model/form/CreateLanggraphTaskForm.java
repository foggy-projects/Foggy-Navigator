package com.foggy.navigator.langgraph.worker.model.form;

import lombok.Data;

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
}
