package com.foggy.navigator.common.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Worker-to-Navigator contract for one complete native subtask state snapshot. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NativeSubtaskUpdatePayload {

    @JsonAlias("contract_version")
    private Integer contractVersion;

    @JsonAlias("subtask_id")
    private String subtaskId;

    @JsonAlias("parent_subtask_id")
    private String parentSubtaskId;

    private Integer depth;
    private String label;
    private String role;
    private String status;
    private String activity;
    private String message;

    @JsonAlias("duration_ms")
    private Long durationMs;

    @JsonAlias("started_at")
    private String startedAt;

    @JsonAlias("updated_at")
    private String updatedAt;

    @JsonAlias("completed_at")
    private String completedAt;
}
