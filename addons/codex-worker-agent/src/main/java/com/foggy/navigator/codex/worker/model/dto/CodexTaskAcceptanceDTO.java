package com.foggy.navigator.codex.worker.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class CodexTaskAcceptanceDTO {
    @JsonAlias("task_id")
    private String taskId;
    private String status;
}
