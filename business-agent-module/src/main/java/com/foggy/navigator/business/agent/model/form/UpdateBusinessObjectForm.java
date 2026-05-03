package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class UpdateBusinessObjectForm {
    private String name;
    private String description;
    private String domain;
    private String status;
}
