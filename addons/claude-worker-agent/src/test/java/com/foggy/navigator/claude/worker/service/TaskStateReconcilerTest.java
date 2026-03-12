package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TaskStateReconciler 单元测试 — L1
 * <p>
 * 测试四象限状态机逻辑：
 * Q1: DB active + CLI alive → touchAlive
 * Q2: DB active + CLI dead → miss count → eventually COMPLETED
 * Q3: DB done + CLI alive → orphan detection
 * Q4: DB done + CLI dead → cleanup orphan
 */
@ExtendWith(MockitoExtension.class)
class TaskStateReconcilerTest {

    @Mock private ClaudeTaskRepository taskRepository;
    @Mock private ClaudeWorkerRepository workerRepository;
    @Mock private ClaudeWorkerService workerService;
    @Mock private ClaudeTaskService taskService;
    @Mock private WorkerStreamRelay streamRelay;
    @Mock private ClaudeWorkerClient workerClient;

    private TaskStateReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new TaskStateReconciler(
                taskRepository, workerRepository, workerService, taskService, streamRelay);
    }

    // ---- 象限 1: DB active + CLI alive → touchAlive ----

    @Test
    void reconcileAll_activeTask_cliAlive_touchAlive() {
        ClaudeWorkerEntity worker = buildWorker("w1");
        when(workerRepository.findAll()).thenReturn(List.of(worker));
        when(workerService.createClient(worker)).thenReturn(workerClient);

        // CLI processes: one alive process matching task
        Map<String, Object> proc = Map.of("pid", 12345, "foggy_task_id", "task-1");
        when(workerClient.listCliProcesses())
                .thenReturn(Mono.just(Map.of("processes", List.of(proc))));

        // Active task in DB
        ClaudeTaskEntity task = buildTask("task-1", "w1", "RUNNING");
        when(taskRepository.findByWorkerIdAndStatusIn(eq("w1"), anyList()))
                .thenReturn(List.of(task));
        when(streamRelay.hasActiveStream("task-1")).thenReturn(true);

        reconciler.reconcileAll();

        verify(taskService).touchAlive("task-1");
    }

    // ---- 象限 2: DB active + CLI dead — miss counting ----

    @Test
    void reconcileAll_activeTask_cliDead_newTaskGrace_skips() {
        ClaudeWorkerEntity worker = buildWorker("w1");
        when(workerRepository.findAll()).thenReturn(List.of(worker));
        when(workerService.createClient(worker)).thenReturn(workerClient);

        // No CLI processes
        when(workerClient.listCliProcesses())
                .thenReturn(Mono.just(Map.of("processes", List.of())));

        // Task just created (within grace period)
        ClaudeTaskEntity task = buildTask("task-1", "w1", "RUNNING");
        task.setCreatedAt(LocalDateTime.now().minusSeconds(30)); // < 2 minutes
        when(taskRepository.findByWorkerIdAndStatusIn(eq("w1"), anyList()))
                .thenReturn(List.of(task));

        reconciler.reconcileAll();

        // Should skip — no touchAlive or miss counting
        verify(taskService, never()).touchAlive(anyString());
        verify(taskService, never()).reconcilerCompleteTask(anyString(), anyString());
    }

    @Test
    void reconcileAll_activeTask_cliDead_workerHasUnsyncedEvents_reconnects() {
        ClaudeWorkerEntity worker = buildWorker("w1");
        when(workerRepository.findAll()).thenReturn(List.of(worker));
        when(workerService.createClient(worker)).thenReturn(workerClient);

        // No CLI processes
        when(workerClient.listCliProcesses())
                .thenReturn(Mono.just(Map.of("processes", List.of())));

        // Old task (past grace period)
        ClaudeTaskEntity task = buildTask("task-1", "w1", "RUNNING");
        task.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        when(taskRepository.findByWorkerIdAndStatusIn(eq("w1"), anyList()))
                .thenReturn(List.of(task));

        // Worker has more events than Java
        when(workerClient.getTaskStatus("task-1"))
                .thenReturn(Mono.just(Map.of("latest_seq", 10, "closed", false)));
        when(streamRelay.getLastAckedSeq("task-1"))
                .thenReturn(new AtomicInteger(5));

        reconciler.reconcileAll();

        verify(streamRelay).reconnectTask("task-1", "session-1", "w1");
        verify(taskService, never()).reconcilerCompleteTask(anyString(), anyString());
    }

    @Test
    void reconcileAll_activeTask_cliDead_workerClosed_seqAligned_waits() {
        ClaudeWorkerEntity worker = buildWorker("w1");
        when(workerRepository.findAll()).thenReturn(List.of(worker));
        when(workerService.createClient(worker)).thenReturn(workerClient);

        when(workerClient.listCliProcesses())
                .thenReturn(Mono.just(Map.of("processes", List.of())));

        ClaudeTaskEntity task = buildTask("task-1", "w1", "RUNNING");
        task.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        when(taskRepository.findByWorkerIdAndStatusIn(eq("w1"), anyList()))
                .thenReturn(List.of(task));

        // Worker closed with aligned seq
        when(workerClient.getTaskStatus("task-1"))
                .thenReturn(Mono.just(Map.of("latest_seq", 5, "closed", true)));
        when(streamRelay.getLastAckedSeq("task-1"))
                .thenReturn(new AtomicInteger(5));

        reconciler.reconcileAll();

        // Should NOT mark as completed — just wait
        verify(taskService, never()).reconcilerCompleteTask(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconcileAll_activeTask_cliDead_miss3_confirmedDead_completesTask() throws Exception {
        // Pre-populate miss count to 2 (one more miss will trigger completion)
        ConcurrentHashMap<String, Integer> missMap = getMissCountMap();
        missMap.put("task-1", 2);

        ClaudeWorkerEntity worker = buildWorker("w1");
        when(workerRepository.findAll()).thenReturn(List.of(worker));
        when(workerService.createClient(worker)).thenReturn(workerClient);

        when(workerClient.listCliProcesses())
                .thenReturn(Mono.just(Map.of("processes", List.of())));

        ClaudeTaskEntity task = buildTask("task-1", "w1", "RUNNING");
        task.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        when(taskRepository.findByWorkerIdAndStatusIn(eq("w1"), anyList()))
                .thenReturn(List.of(task));

        // Worker has no recovery (null or worker query returns same seq)
        when(workerClient.getTaskStatus("task-1"))
                .thenReturn(Mono.just(Map.of("latest_seq", 0)))
                .thenReturn(Mono.just(Map.of("cli_alive", false)));

        when(streamRelay.getLastAckedSeq("task-1")).thenReturn(new AtomicInteger(0));

        reconciler.reconcileAll();

        verify(taskService).reconcilerCompleteTask(eq("task-1"), contains("CLI process exited"));
    }

    // ---- 象限 3: DB done + CLI alive → orphan ----

    @Test
    void reconcileAll_orphanProcess_detected() {
        ClaudeWorkerEntity worker = buildWorker("w1");
        when(workerRepository.findAll()).thenReturn(List.of(worker));
        when(workerService.createClient(worker)).thenReturn(workerClient);

        // CLI process still alive but no active DB task
        Map<String, Object> proc = Map.of("pid", 999, "foggy_task_id", "completed-task");
        when(workerClient.listCliProcesses())
                .thenReturn(Mono.just(Map.of("processes", List.of(proc))));

        // No active tasks
        when(taskRepository.findByWorkerIdAndStatusIn(eq("w1"), anyList()))
                .thenReturn(List.of());

        reconciler.reconcileAll();

        // Orphan should be recorded
        Instant firstSeen = reconciler.getOrphanFirstSeen("w1", 999);
        assertNotNull(firstSeen);
    }

    // ---- 象限 4: cleanup ----

    @Test
    void reconcileAll_orphanProcess_disappears_cleaned() throws Exception {
        // Pre-set an orphan record
        ConcurrentHashMap<String, Instant> orphanMap = getOrphanMap();
        orphanMap.put("w1:999", Instant.now().minusSeconds(120));

        ClaudeWorkerEntity worker = buildWorker("w1");
        when(workerRepository.findAll()).thenReturn(List.of(worker));
        when(workerService.createClient(worker)).thenReturn(workerClient);

        // No processes alive anymore
        when(workerClient.listCliProcesses())
                .thenReturn(Mono.just(Map.of("processes", List.of())));
        when(taskRepository.findByWorkerIdAndStatusIn(eq("w1"), anyList()))
                .thenReturn(List.of());

        reconciler.reconcileAll();

        // Orphan record should be cleaned
        assertNull(reconciler.getOrphanFirstSeen("w1", 999));
    }

    // ---- ESN seq gap 检测 ----

    @Test
    void reconcileAll_activeTask_noActiveStream_seqGap_reconnects() {
        ClaudeWorkerEntity worker = buildWorker("w1");
        when(workerRepository.findAll()).thenReturn(List.of(worker));
        when(workerService.createClient(worker)).thenReturn(workerClient);

        Map<String, Object> proc = Map.of("pid", 111, "foggy_task_id", "task-1");
        when(workerClient.listCliProcesses())
                .thenReturn(Mono.just(Map.of("processes", List.of(proc))));

        ClaudeTaskEntity task = buildTask("task-1", "w1", "RUNNING");
        when(taskRepository.findByWorkerIdAndStatusIn(eq("w1"), anyList()))
                .thenReturn(List.of(task));

        // No active SSE stream
        when(streamRelay.hasActiveStream("task-1")).thenReturn(false);

        // Worker has more events
        when(workerClient.getTaskStatus("task-1"))
                .thenReturn(Mono.just(Map.of("latest_seq", 15)));
        when(streamRelay.getLastAckedSeq("task-1"))
                .thenReturn(new AtomicInteger(10));

        reconciler.reconcileAll();

        verify(taskService).touchAlive("task-1");
        verify(streamRelay).reconnectTask("task-1", "session-1", "w1");
    }

    // ---- Worker 离线 ----

    @Test
    void reconcileAll_workerOffline_skips() {
        ClaudeWorkerEntity worker = buildWorker("w1");
        when(workerRepository.findAll()).thenReturn(List.of(worker));
        when(workerService.createClient(worker)).thenReturn(workerClient);

        when(workerClient.listCliProcesses())
                .thenReturn(Mono.error(new RuntimeException("Connection refused")));

        reconciler.reconcileAll();

        // Should not crash, just skip
        verify(taskService, never()).touchAlive(anyString());
        verify(taskService, never()).reconcilerCompleteTask(anyString(), anyString());
    }

    // ---- getOrphanFirstSeen ----

    @Test
    void getOrphanFirstSeen_notOrphan_returnsNull() {
        assertNull(reconciler.getOrphanFirstSeen("w1", 123));
    }

    // ---- helpers ----

    private ClaudeWorkerEntity buildWorker(String workerId) {
        ClaudeWorkerEntity entity = new ClaudeWorkerEntity();
        entity.setWorkerId(workerId);
        entity.setUserId("u1");
        entity.setBaseUrl("http://localhost:3031");
        entity.setAuthToken("enc-token");
        return entity;
    }

    private ClaudeTaskEntity buildTask(String taskId, String workerId, String status) {
        ClaudeTaskEntity task = new ClaudeTaskEntity();
        task.setTaskId(taskId);
        task.setWorkerId(workerId);
        task.setStatus(status);
        task.setSessionId("session-1");
        task.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        return task;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Instant> getOrphanMap() throws Exception {
        Field field = TaskStateReconciler.class.getDeclaredField("orphanFirstSeen");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, Instant>) field.get(reconciler);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Integer> getMissCountMap() throws Exception {
        Field field = TaskStateReconciler.class.getDeclaredField("cliDeadMissCount");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, Integer>) field.get(reconciler);
    }
}
