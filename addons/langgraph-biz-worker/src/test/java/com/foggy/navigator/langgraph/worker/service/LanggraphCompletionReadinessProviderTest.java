package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.spi.task.RuntimeTaskCompletionReadinessProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LanggraphCompletionReadinessProviderTest {

    private static final String DIGEST =
            "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private LanggraphTaskRepository taskRepository;
    private SessionTaskRepository sessionTaskRepository;
    private LanggraphWorkerService workerService;
    private LanggraphWorkerClient client;
    private LanggraphCompletionReadinessProvider provider;

    @BeforeEach
    void setUp() {
        taskRepository = mock(LanggraphTaskRepository.class);
        sessionTaskRepository = mock(SessionTaskRepository.class);
        workerService = mock(LanggraphWorkerService.class);
        client = mock(LanggraphWorkerClient.class);
        provider = new LanggraphCompletionReadinessProvider(
                taskRepository, sessionTaskRepository, workerService);

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId("worker-a");
        when(workerService.getWorkerEntity("worker-a")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);
    }

    @Test
    void completedReceiptCombinesExactWorkerIdentityWithDurableNavigatorDigest() {
        arrangeTask("COMPLETED", completedDurableState(DIGEST));
        when(client.getTaskCompletionReadiness("task-a"))
                .thenReturn(Mono.just(completedWorkerObservation(DIGEST)));

        RuntimeTaskCompletionReadinessProvider.Observation observation =
                provider.inspectCompletionReadiness("task-a", "worker-a", 1);

        assertTrue(observation.identityVerified());
        assertTrue(observation.terminalSignalPresent());
        assertTrue(observation.completionSignalPresent());
        assertTrue(observation.finalOutputPresent());
        assertTrue(observation.finalOutputDurable());
        assertTrue(observation.resultRecoverable());
        assertEquals(DIGEST, observation.finalOutputDigest());
        assertNull(observation.sanitizedErrorCode());
    }

    @Test
    void failedReceiptIsAuthoritativeTerminalMetadataWithoutResultRecoveryClaim() {
        arrangeTask("FAILED", null);
        Map<String, Object> observed = baseWorkerObservation("FAILED");
        observed.put("terminal_signal_source", "LANGGRAPH_BIZ_ERROR_EVENT");
        observed.put("completion_signal_present", false);
        observed.put("terminal_error_code", "LANGGRAPH_PROVIDER_AUTH_FAILED");
        when(client.getTaskCompletionReadiness("task-a")).thenReturn(Mono.just(observed));

        RuntimeTaskCompletionReadinessProvider.Observation observation =
                provider.inspectCompletionReadiness("task-a", "worker-a", 1);

        assertTrue(observation.identityVerified());
        assertTrue(observation.providerTaskTerminal());
        assertEquals("FAILED", observation.providerTerminalStatus());
        assertEquals("LANGGRAPH_PROVIDER_AUTH_FAILED", observation.terminalErrorCode());
        assertFalse(observation.completionSignalPresent());
        assertFalse(observation.resultRecoverable());
        assertNull(observation.sanitizedErrorCode());
    }

    @Test
    void digestMismatchFailsClosedWithStableObservationCode() {
        arrangeTask("COMPLETED", completedDurableState(DIGEST));
        when(client.getTaskCompletionReadiness("task-a"))
                .thenReturn(Mono.just(completedWorkerObservation(
                        "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")));

        RuntimeTaskCompletionReadinessProvider.Observation observation =
                provider.inspectCompletionReadiness("task-a", "worker-a", 1);

        assertTrue(observation.identityVerified());
        assertFalse(observation.resultRecoverable());
        assertNull(observation.finalOutputDigest());
        assertEquals("WORKER_COMPLETION_EVIDENCE_DIGEST_MISMATCH",
                observation.sanitizedErrorCode());
    }

    @Test
    void exactWorkerMismatchStopsBeforeRemoteObservation() {
        arrangeTask("FAILED", null);

        RuntimeTaskCompletionReadinessProvider.Observation observation =
                provider.inspectCompletionReadiness("task-a", "worker-other", 1);

        assertFalse(observation.identityVerified());
        assertEquals("EXPECTED_PHYSICAL_WORKER_MISMATCH", observation.sanitizedErrorCode());
    }

    private void arrangeTask(String status, String taskStateJson) {
        LanggraphTaskEntity task = new LanggraphTaskEntity();
        task.setTaskId("task-a");
        task.setWorkerId("worker-a");
        task.setStatus(status);
        when(taskRepository.findByTaskId("task-a")).thenReturn(Optional.of(task));

        SessionTaskEntity sessionTask = new SessionTaskEntity();
        sessionTask.setTaskId("task-a");
        sessionTask.setProviderTaskId("task-a");
        sessionTask.setTaskStateJson(taskStateJson);
        when(sessionTaskRepository.findByTaskId("task-a")).thenReturn(Optional.of(sessionTask));
    }

    private String completedDurableState(String digest) {
        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("schema", LanggraphTaskService.DURABLE_RESULT_SCHEMA);
        completion.put("finalOutputPresent", true);
        completion.put("finalOutputDurable", true);
        completion.put("finalOutputDigest", digest);
        completion.put("finalOutputRecordedAt", "2026-07-27T10:00:00Z");
        completion.put("structuredOutputPresent", false);
        completion.put("structuredOutputDigest", null);
        completion.put("resultRecoverable", true);
        return ProviderStateCodec.mergeTaskValues(
                null,
                LanggraphTaskService.PROVIDER_TYPE,
                Map.of(LanggraphTaskService.COMPLETION_EVIDENCE_STATE_KEY, completion));
    }

    private Map<String, Object> completedWorkerObservation(String digest) {
        Map<String, Object> observed = baseWorkerObservation("COMPLETED");
        observed.put("final_output_present", true);
        observed.put("final_output_digest", digest);
        observed.put("final_output_recorded_at", "2026-07-27T10:00:00Z");
        observed.put("completion_signal_present", true);
        observed.put("completion_signal_source", "LANGGRAPH_BIZ_RESULT_EVENT");
        observed.put("completion_signal_recorded_at", "2026-07-27T10:00:00Z");
        return observed;
    }

    private Map<String, Object> baseWorkerObservation(String status) {
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("worker_reachable", true);
        observed.put("worker_observed_at", "2026-07-27T10:01:00Z");
        observed.put("worker_task_known", true);
        observed.put("worker_task_state", status);
        observed.put("provider_active_task_present", false);
        observed.put("provider_task_terminal", true);
        observed.put("provider_terminal_status", status);
        observed.put("final_output_present", false);
        observed.put("structured_output_present", false);
        observed.put("terminal_signal_present", true);
        observed.put("terminal_signal_source", "LANGGRAPH_BIZ_RESULT_EVENT");
        observed.put("terminal_signal_recorded_at", "2026-07-27T10:00:00Z");
        observed.put("completion_signal_present", false);
        observed.put("result_recoverable", false);
        observed.put("evidence_schema", "LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1");
        observed.put("provider_task_id", "task-a");
        observed.put("receipt_dispatch_count", 1);
        observed.put("receipt_worker_id", "worker-a");
        observed.put("receipt_task_id", "task-a");
        observed.put("evidence_conflict", false);
        observed.put("sanitized_error_code", null);
        return observed;
    }
}
