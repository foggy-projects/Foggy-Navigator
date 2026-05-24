package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

import java.util.Map;

@Data
public class AgentReadinessPreflightForm {
    private String upstreamUserId;
    private String modelConfigId;
    private String directoryId;
    private Map<String, Object> context;
}
