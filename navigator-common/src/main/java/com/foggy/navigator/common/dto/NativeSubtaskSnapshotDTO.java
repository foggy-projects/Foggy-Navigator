package com.foggy.navigator.common.dto;

import com.foggy.navigator.common.entity.NativeSubtaskStateEntity;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class NativeSubtaskSnapshotDTO {
    public static final String FAILURE_MESSAGE_CODE = "NATIVE_SUBTASK_FAILED";

    private String subtaskId;
    private String parentSubtaskId;
    private Integer depth;
    private String label;
    private String role;
    private String status;
    private String activity;
    private String message;
    private Instant startedAt;
    private Instant updatedAt;
    private Instant completedAt;
    private Long durationMs;
    private Integer lastEventSeq;

    public static NativeSubtaskSnapshotDTO fromEntity(NativeSubtaskStateEntity entity) {
        return NativeSubtaskSnapshotDTO.builder()
                .subtaskId(entity.getSubtaskId())
                .parentSubtaskId(entity.getParentSubtaskId())
                .depth(entity.getDepth())
                .label(entity.getLabel())
                .role(entity.getRole())
                .status(entity.getStatus())
                .activity(entity.getActivity())
                .message("failed".equalsIgnoreCase(entity.getStatus()) ? FAILURE_MESSAGE_CODE : null)
                .startedAt(entity.getStartedAt())
                .updatedAt(entity.getEventUpdatedAt())
                .completedAt(entity.getCompletedAt())
                .durationMs(entity.getDurationMs())
                .lastEventSeq(entity.getLastEventSeq())
                .build();
    }
}
