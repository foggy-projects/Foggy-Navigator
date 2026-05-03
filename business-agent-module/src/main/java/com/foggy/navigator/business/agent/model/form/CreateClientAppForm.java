package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class CreateClientAppForm {
    private String provisioningToken;
    private String name;
    private String description;
    private String ownerUserId;
    private String capabilityDomain;
}
