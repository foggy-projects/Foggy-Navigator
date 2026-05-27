package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class SkillMaterializeResultDTO {
    private String skillId;
    private String scope;
    private String clientAppId;
    private String accountId;
    private String status;
    private String workerId;
    private String workerUrl;
    private String targetSource;
    private Integer workerStatusCode;
    private String workerResponse;
    private List<SkillMaterializeTargetResultDTO> targets;
}
