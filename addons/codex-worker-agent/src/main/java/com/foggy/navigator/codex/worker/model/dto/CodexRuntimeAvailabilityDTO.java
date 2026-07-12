package com.foggy.navigator.codex.worker.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodexRuntimeAvailabilityDTO {

    private Boolean appServerManaged;
    private Boolean modelSupported;
    private Boolean modelAvailable;
    private Boolean ultraAvailable;
    private String blockReason;
}
