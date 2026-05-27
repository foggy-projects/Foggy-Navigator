package com.foggy.navigator.sdk.model;

public class AgentReadinessCheck {
    private String code;
    private String status;
    private String message;
    private String errorCode;
    private String action;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
}
