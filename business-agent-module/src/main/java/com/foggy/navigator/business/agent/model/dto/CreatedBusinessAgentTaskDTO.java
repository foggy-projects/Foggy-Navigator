package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CreatedBusinessAgentTaskDTO extends BusinessAgentTaskDTO {
    // Only return plain text token once upon creation
    private String taskScopedToken;
}
