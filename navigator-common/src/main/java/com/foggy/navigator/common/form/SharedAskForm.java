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
}
