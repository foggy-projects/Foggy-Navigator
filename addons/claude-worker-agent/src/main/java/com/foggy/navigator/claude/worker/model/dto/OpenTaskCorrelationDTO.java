package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OpenTaskCorrelationDTO {

    private String originalTaskId;

    private String recoveryCorrelationKey;

    private Integer attemptNumber;

    private String idempotencyKey;
}
