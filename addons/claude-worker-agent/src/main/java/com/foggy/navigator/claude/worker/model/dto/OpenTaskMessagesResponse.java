package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Open API 任务增量消息响应 — 按 taskId + cursor 轮询
 */
@Data
@Builder
public class OpenTaskMessagesResponse {

    /** 任务 ID */
    private String taskId;

    /** 所属会话上下文 */
    private String contextId;

    /** 消息列表（按时间升序） */
    private List<OpenSessionMessageDTO> messages;

    /** 下一页游标（为 null 表示无更多数据） */
    private String nextCursor;

    /** 是否还有更多消息 */
    private boolean hasMore;
}
