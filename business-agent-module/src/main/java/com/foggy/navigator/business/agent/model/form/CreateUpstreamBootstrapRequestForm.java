package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class CreateUpstreamBootstrapRequestForm {
    private String upstreamSystemId;
    private String requestedTenantId;
    private Boolean multiTenant;
    private String reason;
    private String applicantLabel;
}
