package com.foggy.navigator.sdk.model;

import java.util.List;

public class SkillArtifactTree {
    private String skillId;
    private String artifactVersion;
    private List<SkillArtifactFile> files;

    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }

    public String getArtifactVersion() { return artifactVersion; }
    public void setArtifactVersion(String artifactVersion) { this.artifactVersion = artifactVersion; }

    public List<SkillArtifactFile> getFiles() { return files; }
    public void setFiles(List<SkillArtifactFile> files) { this.files = files; }
}
