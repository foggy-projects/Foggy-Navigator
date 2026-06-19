package com.foggy.navigator.session.dto;

import com.foggy.navigator.common.entity.SessionRelationEntity;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SessionRelationDTO {
    private Long id;
    private String relationType;
    private String targetMode;
    private String sourceSessionId;
    private String sourceMessageId;
    private String targetSessionId;
    private String sourceWorkerId;
    private String sourceDirectoryId;
    private String sourceMilestoneId;
    private String targetWorkerId;
    private String targetDirectoryId;
    private String targetMilestoneId;
    private String targetProviderType;
    private String targetModelConfigId;
    private LocalDateTime createdAt;

    public static SessionRelationDTO fromEntity(SessionRelationEntity entity) {
        if (entity == null) {
            return null;
        }
        return SessionRelationDTO.builder()
                .id(entity.getId())
                .relationType(entity.getRelationType())
                .targetMode(entity.getTargetMode())
                .sourceSessionId(entity.getSourceSessionId())
                .sourceMessageId(entity.getSourceMessageId())
                .targetSessionId(entity.getTargetSessionId())
                .sourceWorkerId(entity.getSourceWorkerId())
                .sourceDirectoryId(entity.getSourceDirectoryId())
                .sourceMilestoneId(entity.getSourceMilestoneId())
                .targetWorkerId(entity.getTargetWorkerId())
                .targetDirectoryId(entity.getTargetDirectoryId())
                .targetMilestoneId(entity.getTargetMilestoneId())
                .targetProviderType(entity.getTargetProviderType())
                .targetModelConfigId(entity.getTargetModelConfigId())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
