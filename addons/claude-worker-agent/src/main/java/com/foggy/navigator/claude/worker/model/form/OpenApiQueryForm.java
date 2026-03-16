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
}
