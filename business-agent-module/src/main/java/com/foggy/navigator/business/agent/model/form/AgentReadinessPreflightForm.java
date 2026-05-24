package com.foggy.navigator.business.agent.model.form;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.util.Map;

@Data
public class AgentReadinessPreflightForm {
    private String upstreamUserId;
    private String modelConfigId;
    @JsonAlias({"model", "modelName", "model_name", "model_variant"})
    private String modelVariant;
    private String directoryId;
    private Map<String, Object> context;
}
