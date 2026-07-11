package com.foggy.navigator.codex.worker.model.form;

import lombok.Data;

@Data
public class CodexRuntimeLifecycleForm {
    /** Required compare-and-set token; the server increments the epoch on success. */
    private Long expectedRoutingEpoch;
}
