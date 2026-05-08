package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class CreateWorkerPoolForm {
    private String poolId;
    private String name;
    private String workerBackend;
    private String routingPolicy;
}
