package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OpenTaskReportRefDTO {

    private String type;

    private String ref;

    private String frameId;

    private String summary;
}
