package com.foggy.navigator.codex.worker.model.form;

import lombok.Data;

/**
 * 注册 Codex Worker 表单
 */
@Data
public class RegisterCodexWorkerForm {
    /** 显示名称 */
    private String name;
    /** Worker 基础 URL，例如 http://localhost:3032 */
    private String baseUrl;
    /** Bearer token（可选） */
    private String authToken;
}
