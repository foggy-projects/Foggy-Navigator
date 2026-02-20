package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 创建任务表单
 */
@Data
public class CreateTaskForm {
    private String workerId;
    private String prompt;
    private String cwd;
    private String directoryId;
    private String model;
    private Integer maxTurns;
    /** Agent Teams JSON (直接传入或由 directoryId 解析) */
    private String agentTeamsJson;
    /** Base64-encoded image attachments JSON: [{name, data, mimeType}] */
    private String images;
}
