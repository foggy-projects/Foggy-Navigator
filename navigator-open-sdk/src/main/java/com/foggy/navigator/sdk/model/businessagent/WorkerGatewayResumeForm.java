package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkerGatewayResumeForm {

    @JsonProperty("approval_result")
    private ApprovalResult approvalResult;

    @JsonProperty("binding_context")
    private BindingContext bindingContext;

    public ApprovalResult getApprovalResult() { return approvalResult; }
    public void setApprovalResult(ApprovalResult approvalResult) { this.approvalResult = approvalResult; }

    public BindingContext getBindingContext() { return bindingContext; }
    public void setBindingContext(BindingContext bindingContext) { this.bindingContext = bindingContext; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApprovalResult {
        @JsonProperty("approval_id")
        private String approvalId;

        private String status;

        @JsonProperty("approved_by")
        private String approvedBy;

        @JsonProperty("approved_at")
        private LocalDateTime approvedAt;

        private String comment;

        public String getApprovalId() { return approvalId; }
        public void setApprovalId(String approvalId) { this.approvalId = approvalId; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getApprovedBy() { return approvedBy; }
        public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }

        public LocalDateTime getApprovedAt() { return approvedAt; }
        public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }

        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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

        public String getClientAppId() { return clientAppId; }
        public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }

        public String getUpstreamUserId() { return upstreamUserId; }
        public void setUpstreamUserId(String upstreamUserId) { this.upstreamUserId = upstreamUserId; }

        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }

        public String getFunctionId() { return functionId; }
        public void setFunctionId(String functionId) { this.functionId = functionId; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        public String getInputHash() { return inputHash; }
        public void setInputHash(String inputHash) { this.inputHash = inputHash; }
    }
}
