package com.foggy.navigator.session.service;

import lombok.Builder;
import lombok.Data;

/**
 * 统一任务回复请求 —— 用于 permission response / user question reply 等场景。
 */
@Data
@Builder
public class TaskReplyRequest {

    /** 回复内容 */
    private String content;

    /** 回复类型（permission_response / user_reply） */
    private String replyType;

    /** 是否批准（permission 场景） */
    private Boolean approved;
}
