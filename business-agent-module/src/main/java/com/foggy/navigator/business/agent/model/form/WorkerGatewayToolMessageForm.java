package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

/**
 * Form for Worker to report tool execution messages back to Java for audit.
 */
@Data
public class WorkerGatewayToolMessageForm {
    private String toolName;       // e.g. "invoke_business_function"
    private String functionId;     // optional, for invoke tools
    private String status;         // SUCCESS, ERROR, APPROVAL_WAIT, SCRIPT_NOT_AVAILABLE
    private String suspendId;      // present if approval_wait
    private String message;        // human-readable detail
    private String idempotencyKey; // optional
}
