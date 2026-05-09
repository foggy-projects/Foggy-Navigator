package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillResourceForm {
    private String path;
    private String content;
    private String sha256;

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
}
