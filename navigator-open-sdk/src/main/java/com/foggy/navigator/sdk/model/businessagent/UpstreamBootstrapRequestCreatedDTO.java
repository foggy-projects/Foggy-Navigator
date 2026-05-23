package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;

public class UpstreamBootstrapRequestCreatedDTO {
    private String requestCode;
    private String requestCodeSuffix;
    private String claimToken;
    private String status;
    private LocalDateTime requestExpiresAt;

    public String getRequestCode() { return requestCode; }
    public void setRequestCode(String requestCode) { this.requestCode = requestCode; }
    public String getRequestCodeSuffix() { return requestCodeSuffix; }
    public void setRequestCodeSuffix(String requestCodeSuffix) { this.requestCodeSuffix = requestCodeSuffix; }
    public String getClaimToken() { return claimToken; }
    public void setClaimToken(String claimToken) { this.claimToken = claimToken; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getRequestExpiresAt() { return requestExpiresAt; }
    public void setRequestExpiresAt(LocalDateTime requestExpiresAt) { this.requestExpiresAt = requestExpiresAt; }
}
