package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class CreateBusinessObjectForm {
    private String objectId;
    private String name;
    private String description;
    private String domain;
    private String status;
}
