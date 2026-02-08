package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * Worker 更新表单
 */
@Data
public class UpdateWorkerForm {
    private String name;
    private String baseUrl;
    private String authToken;
    private String authMode;
}
