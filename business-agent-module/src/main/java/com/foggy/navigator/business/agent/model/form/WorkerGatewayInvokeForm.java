package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class WorkerGatewayInvokeForm {
    private String version;
    private String inputJson;
    private Object input;
    private String idempotencyKey;
}
