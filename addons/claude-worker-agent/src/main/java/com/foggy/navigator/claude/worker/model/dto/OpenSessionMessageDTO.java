package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
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

    /** 消息类型：USER | TEXT | TOOL_CALL | TOOL_RESULT | RESULT | STATE | ERROR */
    private String type;

    /** 稳定事件语义：text_delta | text_complete | progress | heartbeat | retrying | tool_call_summary | tool_result_summary | structured_output | final_marker | error */
    private String eventKind;

    /** 进展事件子类型；仅当 eventKind 表示 progress/heartbeat/retrying 等进展事实时有值 */
    private String progressType;

    /** 消息内容 */
    private String content;

    /** 所属任务对外状态：SUBMITTED | RUNNING | AWAITING_INPUT | COMPLETED | FAILED | CANCELLED */
    private String status;

    /** 是否为任务终态消息 */
    private Boolean terminal;

    /** 终态状态：COMPLETED | FAILED */
    private String terminalStatus;

    /** 扩展元数据 */
    private Map<String, Object> metadata;

    /** 消息级结构化输出；兼容 metadata.structuredOutput / metadata.structured_output */
    private Object structuredOutput;

    /** 用户消息附件；兼容 metadata.attachments，同时给前端稳定顶层字段 */
    private List<Map<String, Object>> attachments;

    /** 消息级执行报告引用 */
    private List<OpenTaskReportRefDTO> reportRefs;

    /** 消息级 artifact 引用 */
    private List<OpenTaskArtifactRefDTO> artifactRefs;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
