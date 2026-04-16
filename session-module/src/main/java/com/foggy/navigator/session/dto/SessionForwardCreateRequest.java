package com.foggy.navigator.session.dto;

import lombok.Data;

@Data
public class SessionForwardCreateRequest {

    private String sourceSessionId;
    private String sourceMessageId;
    private String targetMode;
    private String targetSessionId;
    private String workerId;
    private String directoryId;
    private String cwd;
    private String prompt;
    private String model;
    private String modelConfigId;
    private String permissionMode;
    private String agentId;
    private Integer maxTurns;
    private String agentTeamsConfigId;
    private String agentTeamsJson;
    private String milestoneId;
    private String images;
}
