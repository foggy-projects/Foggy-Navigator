package com.foggy.navigator.business.agent.model.dto;

import lombok.Builder;
import lombok.Data;

/** Sanitized facts about the task referenced by one runtime request. */
@Data
@Builder
public class RuntimeRequestTaskFactsDTO {
    private String taskId;
    private String status;
    private Boolean terminal;
    private String sanitizedErrorCode;
    private String agentCode;
    private String upstreamUserId;
    private String physicalWorkerId;
    private String modelConfigId;
    private String modelVariant;
    private Integer requestedToolCount;
    private Integer effectiveToolCount;
    private String toolScopeKind;
    private String toolScopeSource;
    private Integer requestedFunctionCount;
    private Integer effectiveFunctionCount;
    private String functionScopeSource;
    private Boolean taskTokenFunctionScopeEmpty;
    private String taskTokenStatus;
    private Boolean runtimeDispatched;
    private Boolean modelDispatched;
    private Boolean businessFunctionDispatched;
    private Integer dispatchCount;
    private Integer retryCount;
    private Integer recoveryCount;
}
