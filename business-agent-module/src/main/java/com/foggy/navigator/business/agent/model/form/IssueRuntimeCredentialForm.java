package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IssueRuntimeCredentialForm {
    private LocalDateTime expiresAt;
    private String description;
}
