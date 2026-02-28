package com.foggy.navigator.common.dto.a2a;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A2A Task Status (Google A2A Protocol)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class A2aTaskStatus {
    private A2aTaskState state;
    private String description;
    private Instant timestamp;
}
