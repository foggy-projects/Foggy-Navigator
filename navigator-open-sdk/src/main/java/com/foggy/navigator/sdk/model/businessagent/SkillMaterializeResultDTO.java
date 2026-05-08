package com.foggy.navigator.sdk.model.businessagent;

public class SkillMaterializeResultDTO {
    private String skillId;
    private String scope;
    private String status;
    private String workerUrl;
    private Integer workerStatusCode;
    private String workerResponse;

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getWorkerUrl() {
        return workerUrl;
    }

    public void setWorkerUrl(String workerUrl) {
        this.workerUrl = workerUrl;
    }

    public Integer getWorkerStatusCode() {
        return workerStatusCode;
    }

    public void setWorkerStatusCode(Integer workerStatusCode) {
        this.workerStatusCode = workerStatusCode;
    }

    public String getWorkerResponse() {
        return workerResponse;
    }

    public void setWorkerResponse(String workerResponse) {
        this.workerResponse = workerResponse;
    }
}
