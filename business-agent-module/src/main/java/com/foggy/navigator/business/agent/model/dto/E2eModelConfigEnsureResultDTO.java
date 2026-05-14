package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

@Data
public class E2eModelConfigEnsureResultDTO {
    private String clientAppId;
    private String standard;
    private String mockBaseUrl;
    private String modelConfigId;
    private String modelConfigName;
    private boolean modelCreated;
    private boolean modelUpdated;
    private Long grantId;
    private boolean grantCreated;
    private String grantStatus;
    private Boolean isDefault;
}
