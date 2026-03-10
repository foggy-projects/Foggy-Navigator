package com.foggy.navigator.common.form;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 共享密钥创建表单
 */
@Data
public class SharingKeyCreateForm {

    /** 要共享的 Agent ID（必填） */
    private String agentId;

    /** 标签/描述（如 "给张三用的"） */
    private String label;

    /** 可选系统提示词（注入到用户提问前面作为约束指令） */
    private String systemPrompt;

    /** Claude Worker 最大轮数（可选，默认 1） */
    private Integer maxTurns;

    /** 每日调用次数限额（可选，默认 50） */
    private Integer maxDailyCalls;

    /** 过期时间（可选，null 表示永不过期） */
    private LocalDateTime expiresAt;
}
