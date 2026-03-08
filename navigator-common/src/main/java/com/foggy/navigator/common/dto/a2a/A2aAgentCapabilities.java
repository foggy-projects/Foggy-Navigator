package com.foggy.navigator.common.dto.a2a;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A2A Agent Capabilities (Google A2A Protocol)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class A2aAgentCapabilities {
    private Boolean streaming;
    private Boolean pushNotifications;
    private Boolean stateTransitionHistory;
}
