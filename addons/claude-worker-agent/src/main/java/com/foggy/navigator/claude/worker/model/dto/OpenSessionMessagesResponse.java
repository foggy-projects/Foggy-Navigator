package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Open API 会话消息列表响应 — 按 contextId + cursor 分页
 */
@Data
@Builder
public class OpenSessionMessagesResponse {

    /** 会话上下文 ID */
    private String contextId;

    /** 消息列表（按时间升序） */
    private List<OpenSessionMessageDTO> messages;

    /** 下一页游标 */
    private String nextCursor;

    /** 是否还有更多消息 */
    private boolean hasMore;
}
