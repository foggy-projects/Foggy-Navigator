package com.foggy.navigator.codex.worker.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class CodexAppServerProcessServiceTest {

    @Test
    void groupsTaskBindingsForTheSameEndpointAndPidIntoOneSharedRuntime() {
        Map<String, CodexAppServerProcessService.SharedProcess> grouped = new LinkedHashMap<>();
        CodexAppServerProcessService.collectSnapshot(grouped, "endpoint-1", "http://127.0.0.1:3062",
                Map.of("processes", List.of(
                        Map.of("pid", 4321, "foggy_task_id", "task-one"),
                        Map.of("pid", 4321, "foggy_task_id", "task-two"),
                        Map.of("pid", 7654, "foggy_task_id", "bad task id")
                )));

        assertEquals(1, grouped.size());
        Map<String, Object> process = grouped.values().iterator().next().toPublicMap();
        assertEquals("codex-app-server", process.get("process_type"));
        assertEquals(2, process.get("shared_task_count"));
        assertEquals(List.of("task-one", "task-two"), process.get("foggy_task_ids"));
        assertNull(process.get("process_identity"));
        assertFalse(process.containsKey("endpoint_url"));
    }
}
