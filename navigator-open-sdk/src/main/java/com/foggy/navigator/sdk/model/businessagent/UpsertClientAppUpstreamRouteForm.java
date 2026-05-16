package com.foggy.navigator.sdk.model.businessagent;

public class UpsertClientAppUpstreamRouteForm {
    private String baseUrl;
    private String userTokenHeader;
    private String status;
    private String description;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getUserTokenHeader() { return userTokenHeader; }
    public void setUserTokenHeader(String userTokenHeader) { this.userTokenHeader = userTokenHeader; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
