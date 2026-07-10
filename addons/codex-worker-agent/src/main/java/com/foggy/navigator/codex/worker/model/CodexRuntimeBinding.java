package com.foggy.navigator.codex.worker.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;

@Value
@Builder
public class CodexRuntimeBinding {

    String runtimeId;
    Integer runtimeRevision;
    CodexRuntimeType runtimeType;
    String workerId;
    String endpointUrl;
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    String authToken;
    String instanceId;
    Long routingEpoch;

    public static CodexRuntimeBinding legacySdk(String workerId) {
        return CodexRuntimeBinding.builder()
                .runtimeId("legacy-sdk:" + workerId)
                .runtimeRevision(1)
                .runtimeType(CodexRuntimeType.SDK_EXEC)
                .workerId(workerId)
                .routingEpoch(0L)
                .build();
    }
}
