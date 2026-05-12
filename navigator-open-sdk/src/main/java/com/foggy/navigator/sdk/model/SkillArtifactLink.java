package com.foggy.navigator.sdk.model;

public class SkillArtifactLink {
    private boolean available;
    private String treeUrl;
    private String sliceUrlTemplate;

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }

    public String getTreeUrl() { return treeUrl; }
    public void setTreeUrl(String treeUrl) { this.treeUrl = treeUrl; }

    public String getSliceUrlTemplate() { return sliceUrlTemplate; }
    public void setSliceUrlTemplate(String sliceUrlTemplate) { this.sliceUrlTemplate = sliceUrlTemplate; }
}
