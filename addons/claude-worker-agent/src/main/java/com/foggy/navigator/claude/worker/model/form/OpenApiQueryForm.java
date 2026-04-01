package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * Open API Agent 查询表单
 */
@Data
public class OpenApiQueryForm {

    /** 查询内容（必填） */
    private String question;

    /** 多轮会话标识（可选，首次不传则平台自动生成） */
    private String contextId;

    /** 最大交互轮数（可选，默认 3） */
    private Integer maxTurns;

    /** 原生系统提示词，仅对支持该能力的 Agent 生效 */
    private String systemPrompt;

    /** 首轮附加消息，仅在首次创建会话时拼接到用户消息前面 */
    private String firstMsg;
}
