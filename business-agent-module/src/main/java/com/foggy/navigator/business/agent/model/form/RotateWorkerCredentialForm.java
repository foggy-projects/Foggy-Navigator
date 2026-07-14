package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class RotateWorkerCredentialForm {

    /**
     * Credential lifetime in seconds. When omitted, the service uses its
     * bounded 30-day default. No post-expiry grace period is applied.
     */
    private Long ttlSeconds;
}
