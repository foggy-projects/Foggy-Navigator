package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

import java.util.List;

@Data
public class BatchBindAuthForm {
    private List<String> sessionIds;
    private String authMode;
    private String authToken;
    private String baseUrl;
    private boolean skipExisting = true;
    /** 平台 LLM 模型配置 ID，用于从平台配置中获取 apiKey + baseUrl */
    private String modelConfigId;
}
