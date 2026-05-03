package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class GrantBusinessFunctionForm {
    private String functionId;
    private String version;
    private String status;
}
