package com.foggy.navigator.codex.worker.model.form;

import lombok.Data;
import lombok.ToString;

@Data
public class CodexAppServerEndpointForm {
    private String workerId;
    private String endpointUrl;
    @ToString.Exclude
    private String authToken;
    /** Only used by updates. A blank token otherwise means keep the saved token. */
    private Boolean clearAuthToken;
}
