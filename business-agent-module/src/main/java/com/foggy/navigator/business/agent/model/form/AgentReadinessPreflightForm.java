package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

import java.util.Map;

@Data
public class AgentReadinessPreflightForm {
    private String upstreamUserId;
    private String modelConfigId;
    private Map<String, Object> context;
}
