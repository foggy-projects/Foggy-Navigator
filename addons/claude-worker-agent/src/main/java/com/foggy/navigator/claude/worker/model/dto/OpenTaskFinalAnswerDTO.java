package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class OpenTaskFinalAnswerDTO {

    private Boolean available;

    private String summary;

    private String messageId;

    private String source;

    private LocalDateTime createdAt;
}
