package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.SkillBundleEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SkillBundleDTO {
    private String tenantId;
    private String clientAppId;
    private String scope;
    private String accountId;
    private String skillId;
    private String name;
    private String description;
    private String status;
    private String contextVisibility;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private SkillMaterializeResultDTO materializeResult;

    public static SkillBundleDTO fromEntity(SkillBundleEntity entity) {
        if (entity == null) {
            return null;
        }
        SkillBundleDTO dto = new SkillBundleDTO();
        dto.setTenantId(entity.getTenantId());
        dto.setClientAppId(entity.getClientAppId());
        dto.setScope(entity.getScope());
        dto.setAccountId(entity.getAccountId());
        dto.setSkillId(entity.getSkillId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setStatus(entity.getStatus());
        dto.setContextVisibility(entity.getContextVisibility() == null ? "isolated" : entity.getContextVisibility());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
