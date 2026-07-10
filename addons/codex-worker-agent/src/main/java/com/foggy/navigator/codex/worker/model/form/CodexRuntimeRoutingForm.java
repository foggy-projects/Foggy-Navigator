package com.foggy.navigator.codex.worker.model.form;

import lombok.Data;

@Data
public class CodexRuntimeRoutingForm {
    private Boolean enabled;
    private String routingPolicy;
    private Integer rolloutPercentage;
    private Integer priority;
    /** Required compare-and-set token; the server increments the epoch on success. */
    private Long expectedRoutingEpoch;
}
