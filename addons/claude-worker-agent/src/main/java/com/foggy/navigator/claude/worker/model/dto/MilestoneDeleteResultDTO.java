package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MilestoneDeleteResultDTO {
    private String milestoneId;
    private long sessionCount;
    private boolean deleted;
}
