package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class AccountContextFileWriteForm {
    private String content;
    private String expectedSha256;
}
