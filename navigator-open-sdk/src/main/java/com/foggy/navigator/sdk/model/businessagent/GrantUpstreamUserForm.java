package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class GrantUpstreamUserForm {
    private String upstreamUserId;
    private String status;

    public String getUpstreamUserId() { return upstreamUserId; }
    public void setUpstreamUserId(String upstreamUserId) { this.upstreamUserId = upstreamUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
