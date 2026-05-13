package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

@Data
public class SkillMaterializeResultDTO {
    private String skillId;
    private String scope;
    private String clientAppId;
    private String accountId;
    private String status;
    private String workerUrl;
    private Integer workerStatusCode;
    private String workerResponse;
}
