package com.foggy.navigator.claude.worker.model.dto;

import lombok.Data;

/**
 * SSH 会话信息 — 返回给前端用于恢复终端 tab
 */
@Data
public class SshSessionDTO {
    private String sessionId;
    private String directoryId;
    /** "username@host" */
    private String label;
    private String wsUrl;
    private int cols;
    private int rows;
    private String connectedAt;
    private String lastActivity;
}
