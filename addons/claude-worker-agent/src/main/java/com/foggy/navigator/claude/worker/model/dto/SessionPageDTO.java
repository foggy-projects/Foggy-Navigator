package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 按会话分页的响应 DTO。
 * 每页包含 pageSize 个会话（而非任务），content 是这些会话的所有任务。
 */
@Data
@Builder
public class SessionPageDTO {
    /** 当前页所有会话的任务列表 */
    private List<TaskDTO> content;
    /** 会话总数 */
    private long totalSessions;
    /** 当前页码（0-based） */
    private int page;
    /** 每页会话数 */
    private int size;
}
