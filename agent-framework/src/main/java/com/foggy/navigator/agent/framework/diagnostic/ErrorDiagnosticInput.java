package com.foggy.navigator.agent.framework.diagnostic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Allowlisted diagnostic input accepted by platform persistence. Arbitrary
 * request objects, prompts, tool input/output and environment maps are
 * deliberately absent from this model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorDiagnosticInput {
    public static final int SCHEMA_VERSION = 1;
    public static final int REDACTION_VERSION = 1;

    private String exceptionType;
    private String diagnosticText;
    private String providerStatus;
    private Integer httpStatus;
    private Integer retryCount;
    private String workerLabel;
}
