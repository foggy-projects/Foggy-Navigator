package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class EnsureE2eModelConfigForm {
    private String standard;
    private String mockBaseUrl;
    private Boolean setDefault;
}
