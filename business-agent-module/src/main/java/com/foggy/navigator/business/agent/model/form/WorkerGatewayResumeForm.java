package com.foggy.navigator.business.agent.model.form;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class WorkerGatewayResumeForm {

    @JsonProperty("approval_result")
    private ApprovalResult approvalResult;

    @JsonProperty("binding_context")
    private BindingContext bindingContext;

    @Data
    public static class ApprovalResult {
        @JsonProperty("approval_id")
        private String approvalId;

        private String status;

        @JsonProperty("approved_by")
        private String approvedBy;

        @JsonProperty("approved_at")
        private LocalDateTime approvedAt;

        private String comment;
    }

    @Data
    public static class BindingContext {
        @JsonProperty("client_app_id")
        private String clientAppId;

        @JsonProperty("upstream_user_id")
        private String upstreamUserId;

        @JsonProperty("task_id")
        private String taskId;

        @JsonProperty("session_id")
        private String sessionId;

        @JsonProperty("function_id")
        private String functionId;

        private String version;

        @JsonProperty("input_hash")
        private String inputHash;
    }
}
