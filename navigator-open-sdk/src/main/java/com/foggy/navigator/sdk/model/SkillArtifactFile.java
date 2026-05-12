package com.foggy.navigator.sdk.model;

public class SkillArtifactFile {
    private String path;
    private String type;
    private long size;
    private int lineCount;
    private String sliceUrl;

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public int getLineCount() { return lineCount; }
    public void setLineCount(int lineCount) { this.lineCount = lineCount; }

    public String getSliceUrl() { return sliceUrl; }
    public void setSliceUrl(String sliceUrl) { this.sliceUrl = sliceUrl; }
}
