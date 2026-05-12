package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SkillArtifactTreeDTO {
    private String skillId;
    private String artifactVersion;
    private List<SkillArtifactFileDTO> files = new ArrayList<>();
}
