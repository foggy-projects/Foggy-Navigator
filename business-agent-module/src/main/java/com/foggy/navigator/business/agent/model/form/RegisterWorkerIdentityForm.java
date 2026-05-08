package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class RegisterWorkerIdentityForm {
    private String workerId;
    private String workerBackend;
    private String baseUrl;
    private String version;
    private String identityToken;
}
