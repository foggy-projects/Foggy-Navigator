package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Open API 会话消息 DTO — 对外统一消息结构
 */
@Data
@Builder
public class OpenSessionMessageDTO {

    /** 消息 ID */
    private String messageId;

    /** 所属会话上下文 */
    private String contextId;

    /** 所属任务 */
    private String taskId;

    /** 角色：user | assistant | tool | system */
    private String role;

    /** 消息类型：USER | TEXT | TOOL_CALL | TOOL_RESULT | STATE | ERROR */
    private String type;

    /** 消息内容 */
    private String content;

    /** 扩展元数据 */
    private Map<String, Object> metadata;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
