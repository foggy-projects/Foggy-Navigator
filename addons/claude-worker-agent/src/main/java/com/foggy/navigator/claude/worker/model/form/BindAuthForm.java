package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 绑定 Auth 配置表单（包含模型映射）
 */
@Data
public class BindAuthForm {
    private String authMode;
    private String authToken;
    private String baseUrl;
    private String haikuModelName;
    private String sonnetModelName;
    private String opusModelName;
}
