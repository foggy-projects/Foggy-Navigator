package com.foggy.navigator.codereview.model.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * GitLab Merge Request Webhook 事件载荷
 * <p>
 * 仅映射审核流程所需字段，其余忽略。
 *
 * @see <a href="https://docs.gitlab.com/ee/user/project/integrations/webhook_events.html#merge-request-events">GitLab MR Events</a>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitLabMrWebhookPayload {

    /** 事件类型，应为 "merge_request" */
    @JsonProperty("object_kind")
    private String objectKind;

    /** 项目信息 */
    private Project project;

    /** MR 详情 */
    @JsonProperty("object_attributes")
    private ObjectAttributes objectAttributes;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Project {
        private Long id;
        private String name;
        @JsonProperty("path_with_namespace")
        private String pathWithNamespace;
        @JsonProperty("web_url")
        private String webUrl;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ObjectAttributes {
        /** MR 内部 ID（iid） */
        private Long iid;
        private String title;
        @JsonProperty("source_branch")
        private String sourceBranch;
        @JsonProperty("target_branch")
        private String targetBranch;
        /** 动作: open, close, reopen, update, merge, approved, unapproved */
        private String action;
        private String state;
        private String url;
        @JsonProperty("last_commit")
        private LastCommit lastCommit;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LastCommit {
        private String id;
        private String message;
    }
}
