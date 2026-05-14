package com.foggy.navigator.business.agent.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class ClientAppControlPlanePrincipal {
    private boolean admin;
    private String tenantId;
    private String clientAppId;
    private String credentialId;
    private String actorUserId;
    private Set<String> scopes;
}
