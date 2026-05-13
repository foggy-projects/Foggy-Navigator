package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

@Data
public class SkillArtifactSliceDTO {
    private String skillId;
    private String path;
    private String encoding;
    private String lineEnding;
    private int startLine;
    private int startColumn;
    private int endLine;
    private int endColumn;
    private int nextLine;
    private int nextColumn;
    private int maxChars;
    private boolean truncated;
    private int totalLines;
    private String content;
}
