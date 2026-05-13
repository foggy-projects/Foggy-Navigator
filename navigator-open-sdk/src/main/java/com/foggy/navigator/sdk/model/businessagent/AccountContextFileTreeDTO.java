package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountContextFileTreeDTO {
    private String accountId;
    private List<AccountContextFileDTO> files = new ArrayList<>();

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public List<AccountContextFileDTO> getFiles() { return files; }
    public void setFiles(List<AccountContextFileDTO> files) { this.files = files; }
}
