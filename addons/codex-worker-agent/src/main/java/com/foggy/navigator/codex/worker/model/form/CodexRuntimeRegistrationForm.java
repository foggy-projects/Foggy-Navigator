package com.foggy.navigator.codex.worker.model.form;

import lombok.Data;
import lombok.ToString;

@Data
public class CodexRuntimeRegistrationForm {
    private String runtimeId;
    private Integer revision;
    private String workerId;
    private String runtimeType = "APP_SERVER";
    private String endpointUrl;
    @ToString.Exclude
    private String authToken;
    private String instanceId;
    private Boolean enabled = false;
    private String routingPolicy = "DARK";
    private Integer rolloutPercentage = 0;
    private Integer priority = 0;
    private String expectedCliVersion = "0.144.1";
    private String expectedSchemaDigest =
            "6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f";
}
