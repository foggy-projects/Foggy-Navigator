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
}
