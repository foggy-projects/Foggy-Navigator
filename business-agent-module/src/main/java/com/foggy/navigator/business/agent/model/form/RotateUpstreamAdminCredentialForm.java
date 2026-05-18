package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RotateUpstreamAdminCredentialForm {
    private LocalDateTime credentialExpiresAt;
}
