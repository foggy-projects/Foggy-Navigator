package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Open API 会话列表响应 — cursor 分页
 */
@Data
@Builder
public class OpenSessionListResponse {

    /** 会话列表 */
    private List<OpenSessionSummaryDTO> sessions;

    /** 下一页游标 */
    private String nextCursor;

    /** 是否还有更多会话 */
    private boolean hasMore;
}
