package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class RegisterWorkerIdentityForm {
    private String workerId;
    private String workerBackend;
    private String baseUrl;
    private String version;

    /**
     * Development-only legacy v0 token. It is not valid for strict external
     * Worker authentication. Use the credential rotation endpoint to obtain a
     * server-generated, expiring credential.
     */
    private String identityToken;
}
