package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * Open API Agent 查询表单
 */
@Data
public class OpenApiQueryForm {

    /** 查询内容（合同字段，与 question 等价，任选其一） */
    private String message;

    /** 查询内容（兼容旧字段，与 message 等价） */
    private String question;

    /** 多轮会话标识（可选，首次不传则平台自动生成） */
    private String contextId;

    /** 最大交互轮数（可选，默认 3） */
    private Integer maxTurns;

    /** 原生系统提示词，仅对支持该能力的 Agent 生效 */
    private String systemPrompt;

    /** 首轮附加消息，仅在首次创建会话时拼接到用户消息前面 */
    private String firstMsg;

    /** 扩展元数据 */
    private java.util.Map<String, Object> metadata;

    /**
     * 获取实际消息内容（优先 message，回退 question）
     */
    public String resolveMessage() {
        if (message != null && !message.isBlank()) return message;
        return question;
    }
}
