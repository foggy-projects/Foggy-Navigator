package com.foggy.navigator.business.agent.model.form;

import com.foggy.navigator.common.enums.LlmModelCategory;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ClientAppModelConfigForm {
    private String name;
    private LlmModelCategory category;
    private String baseUrl;
    private String modelName;
    private String apiKey;
    private Boolean setDefault;
    private Map<String, String> envVars;
    private List<String> availableModels;
    private String runtimeBudgetPresetKey;
    private String runtimeBudgetOverrideJson;
}
