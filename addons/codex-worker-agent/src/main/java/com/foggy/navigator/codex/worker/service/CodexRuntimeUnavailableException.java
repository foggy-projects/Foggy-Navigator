package com.foggy.navigator.codex.worker.service;

public class CodexRuntimeUnavailableException extends IllegalStateException {

    private final String code;

    public CodexRuntimeUnavailableException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
