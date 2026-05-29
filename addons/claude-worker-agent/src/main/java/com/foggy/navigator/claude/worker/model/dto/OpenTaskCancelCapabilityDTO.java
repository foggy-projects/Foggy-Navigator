package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OpenTaskCancelCapabilityDTO {

    private Boolean cancelSupported;

    private String cancelMode;

    private Boolean cleanupSupported;

    private List<String> backendLimitations;
}
