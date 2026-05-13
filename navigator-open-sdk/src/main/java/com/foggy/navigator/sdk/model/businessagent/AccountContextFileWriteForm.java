package com.foggy.navigator.sdk.model.businessagent;

public class AccountContextFileWriteForm {
    private String content;
    private String expectedSha256;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getExpectedSha256() { return expectedSha256; }
    public void setExpectedSha256(String expectedSha256) { this.expectedSha256 = expectedSha256; }
}
