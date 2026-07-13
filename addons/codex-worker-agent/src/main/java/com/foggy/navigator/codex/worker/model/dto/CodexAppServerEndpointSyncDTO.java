package com.foggy.navigator.codex.worker.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CodexAppServerEndpointSyncDTO {
    private CodexAppServerEndpointDTO endpoint;
    private CodexRuntimeDTO runtime;
    private Boolean runtimeCreated;
    private Boolean runtimeRestored;
}
