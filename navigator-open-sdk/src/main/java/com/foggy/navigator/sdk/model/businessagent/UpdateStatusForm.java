package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateStatusForm {
    private String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
