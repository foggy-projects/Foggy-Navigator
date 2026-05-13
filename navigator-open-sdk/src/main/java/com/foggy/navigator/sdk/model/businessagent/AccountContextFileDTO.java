package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountContextFileDTO {
    private String fileName;
    private boolean exists;
    private long size;
    private int lineCount;
    private String sha256;
    private boolean truncated;
    private boolean writable;
    private String content;

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public boolean isExists() { return exists; }
    public void setExists(boolean exists) { this.exists = exists; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public int getLineCount() { return lineCount; }
    public void setLineCount(int lineCount) { this.lineCount = lineCount; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
    public boolean isWritable() { return writable; }
    public void setWritable(boolean writable) { this.writable = writable; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
