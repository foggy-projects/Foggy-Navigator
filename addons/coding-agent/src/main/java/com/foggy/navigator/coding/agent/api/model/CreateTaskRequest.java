package com.foggy.navigator.coding.agent.api.model;

import lombok.Data;

@Data
public class CreateTaskRequest {
    private String environmentId;
    private String instruction;
}
