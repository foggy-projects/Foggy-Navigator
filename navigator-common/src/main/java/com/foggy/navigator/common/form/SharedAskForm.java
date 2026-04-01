package com.foggy.navigator.common.form;

import lombok.Data;

/**
 * 外部用户提问表单（通过 Sharing Key 调用）
 */
@Data
public class SharedAskForm {

    /** 问题内容（必填） */
    private String question;

    /** 多轮对话上下文 ID（可选，用于追问） */
    private String contextId;

    /** 上下文别名（可选，用于按别名复用会话） */
    private String contextAlias;

    /** 原生系统提示词，仅对支持该能力的 Agent 生效 */
    private String systemPrompt;

    /** 首轮附加消息，仅在首次创建会话时拼接到用户消息前面 */
    private String firstMsg;
}
