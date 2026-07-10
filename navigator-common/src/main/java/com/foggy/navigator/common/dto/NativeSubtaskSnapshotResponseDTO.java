package com.foggy.navigator.common.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class NativeSubtaskSnapshotResponseDTO {
    private Integer contractVersion;
    private String taskId;
    private String sessionId;
    private String providerType;
    private Integer latestEventSeq;
    private List<NativeSubtaskSnapshotDTO> subtasks;
}
