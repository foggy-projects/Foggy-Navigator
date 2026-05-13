package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

@Data
public class SkillArtifactFileDTO {
    private String path;
    private String type;
    private long size;
    private int lineCount;
    private String sliceUrl;
}
