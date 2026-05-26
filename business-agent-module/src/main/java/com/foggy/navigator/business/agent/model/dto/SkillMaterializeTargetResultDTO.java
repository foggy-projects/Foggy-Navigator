package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

@Data
public class SkillMaterializeTargetResultDTO {
    private String workerId;
    private String workerUrl;
    private String source;
    private String status;
    private Integer workerStatusCode;
    private String workerResponse;
}
