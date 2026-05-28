package com.foggy.navigator.common.dto;

import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.Data;

@Data
public class LlmModelConfigOwnerRepairResultDTO {
    private String modelConfigId;
    private String tenantId;
    private ResourceOwnerType previousOwnerType;
    private String previousOwnerId;
    private ResourceOwnerType ownerType;
    private String ownerId;
    private Boolean previousEnabled;
    private Boolean enabled;
}
