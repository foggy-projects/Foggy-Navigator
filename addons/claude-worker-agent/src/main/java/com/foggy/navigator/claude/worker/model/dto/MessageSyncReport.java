package com.foggy.navigator.claude.worker.model.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 消息同步报告（策略 B 返回）
 */
@Data
public class MessageSyncReport {
    /** 同步前平台侧消息计数 */
    private MessageCount platformBefore;
    /** Worker 侧消息计数 */
    private MessageCount workerTotal;
    /** 本次导入的消息数 */
    private int imported;
    /** 同步后平台侧消息计数 */
    private MessageCount platformAfter;
    /** 缺失消息预览（最多 10 条，每条 content 截断 200 字） */
    private List<Map<String, Object>> missingPreview;
}
