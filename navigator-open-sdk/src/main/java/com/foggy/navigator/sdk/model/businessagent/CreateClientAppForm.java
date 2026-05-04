package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateClientAppForm {
    private String provisioningToken;
    private String name;
    private String description;
    private String ownerUserId;
    private String capabilityDomain;

    public String getProvisioningToken() { return provisioningToken; }
    public void setProvisioningToken(String provisioningToken) { this.provisioningToken = provisioningToken; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getCapabilityDomain() { return capabilityDomain; }
    public void setCapabilityDomain(String capabilityDomain) { this.capabilityDomain = capabilityDomain; }
}
