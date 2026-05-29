package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OpenTaskStructuredOutputDTO {

    private Boolean available;

    private Object value;

    private String source;
}
