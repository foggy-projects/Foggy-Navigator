package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

@Data
public class BindAuthForm {
    private String authMode;
    private String authToken;
    private String baseUrl;
}
