package com.foggy.navigator.sdk.model.businessagent;

public class CreateUpstreamBootstrapRequestForm {
    private String upstreamSystemId;
    private String requestedTenantId;
    private Boolean multiTenant;
    private String reason;
    private String applicantLabel;

    public String getUpstreamSystemId() { return upstreamSystemId; }
    public void setUpstreamSystemId(String upstreamSystemId) { this.upstreamSystemId = upstreamSystemId; }
    public String getRequestedTenantId() { return requestedTenantId; }
    public void setRequestedTenantId(String requestedTenantId) { this.requestedTenantId = requestedTenantId; }
    public Boolean getMultiTenant() { return multiTenant; }
    public void setMultiTenant(Boolean multiTenant) { this.multiTenant = multiTenant; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getApplicantLabel() { return applicantLabel; }
    public void setApplicantLabel(String applicantLabel) { this.applicantLabel = applicantLabel; }
}
