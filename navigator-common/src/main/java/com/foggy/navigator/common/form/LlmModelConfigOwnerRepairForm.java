package com.foggy.navigator.common.form;

import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.Data;

@Data
public class LlmModelConfigOwnerRepairForm {
    private String tenantId;
    private ResourceOwnerType ownerType;
    private String ownerId;
    private Boolean enabled;
}
