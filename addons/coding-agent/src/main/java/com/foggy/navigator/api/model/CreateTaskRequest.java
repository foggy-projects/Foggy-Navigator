package com.foggy.navigator.api.model;

import lombok.Data;

@Data
public class CreateTaskRequest {
    private String environmentId;
    private String instruction;
}
