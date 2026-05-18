package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class EnsureUpstreamClientAppForm {
    private String targetTenantId;
    private String upstreamRef;
    private String name;
    private String description;
    private String ownerUserId;
    private String capabilityDomain;
}
