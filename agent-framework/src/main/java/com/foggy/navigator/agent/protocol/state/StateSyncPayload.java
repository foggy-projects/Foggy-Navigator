package com.foggy.navigator.agent.protocol.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 状态同步载荷
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StateSyncPayload {
    private String stateId;
    private SyncMode mode;
    private Map<String, Object> data;
    private String jsonPatch;  // JSON Patch (RFC 6902) for PATCH mode
}
