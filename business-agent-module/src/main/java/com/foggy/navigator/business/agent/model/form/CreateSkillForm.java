package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class CreateSkillForm {
    private String skillId;
    private String name;
    private String description;
    private String status;
}
