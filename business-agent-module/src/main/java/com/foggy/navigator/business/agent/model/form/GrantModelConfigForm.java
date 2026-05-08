package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class GrantModelConfigForm {
    private String modelConfigId;
    private Boolean isDefault;
    private String grantScope;
}
