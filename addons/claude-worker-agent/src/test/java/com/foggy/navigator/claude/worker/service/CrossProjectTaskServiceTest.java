package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.claude.worker.model.dto.CrossProjectPhaseDTO;
import com.foggy.navigator.claude.worker.model.dto.CrossProjectTaskDTO;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.dto.WorkingDirectoryDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.CrossProjectPhaseEntity;
import com.foggy.navigator.claude.worker.model.entity.CrossProjectTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.model.form.CreateCrossProjectTaskForm;
import com.foggy.navigator.claude.worker.repository.CrossProjectPhaseRepository;
import com.foggy.navigator.claude.worker.repository.CrossProjectTaskRepository;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrossProjectTaskServiceTest {

    private CrossProjectTaskRepository taskRepository;
    private CrossProjectPhaseRepository phaseRepository;
    private ClaudeTaskService claudeTaskService;
    private CodingAgentService codingAgentService;
    private WorkingDirectoryService directoryService;
    private WorkingDirectoryRepository directoryRepository;
    private ApplicationEventPublisher eventPublisher;
    private CrossProjectTaskService service;

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String CONTEXT_ID = "ctx-12345678";
    private static final String DIR_A = "dir-a";
    private static final String DIR_B = "dir-b";
    private static final String WORKER_ID = "worker-1";
    private static final String AGENT_ID = "agent-001";

    @BeforeEach
    void setUp() {
        taskRepository = mock(CrossProjectTaskRepository.class);
        phaseRepository = mock(CrossProjectPhaseRepository.class);
        claudeTaskService = mock(ClaudeTaskService.class);
        codingAgentService = mock(CodingAgentService.class);
        directoryService = mock(WorkingDirectoryService.class);
        directoryRepository = mock(WorkingDirectoryRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new CrossProjectTaskService(
                taskRepository, phaseRepository, claudeTaskService,
                codingAgentService, directoryService, directoryRepository, eventPublisher);

        // Default: save returns argument with timestamps set
        when(taskRepository.save(any())).thenAnswer(inv -> {
            CrossProjectTaskEntity e = inv.getArgument(0);
            if (e.getCreatedAt() == null) e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            return e;
        });
        when(phaseRepository.save(any())).thenAnswer(inv -> {
            CrossProjectPhaseEntity e = inv.getArgument(0);
            if (e.getCreatedAt() == null) e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            return e;
        });
    }

    // ========== Create ==========

    @Nested
    class CreateTests {

        @Test
        void createTask_success() {
            // Arrange: directory lookup for resolving workerId
            when(directoryRepository.findByDirectoryIdAndUserId(DIR_A, USER_ID))
                    .thenReturn(Optional.of(createDirectory(DIR_A, WORKER_ID)));
            when(directoryRepository.findByDirectoryIdAndUserId(DIR_B, USER_ID))
                    .thenReturn(Optional.of(createDirectory(DIR_B, WORKER_ID)));

            // For getTask() enrichment after save
            when(taskRepository.findByContextIdAndUserId(anyString(), eq(USER_ID)))
                    .thenAnswer(inv -> {
                        String cid = inv.getArgument(0);
                        return Optional.of(createTaskEntity(cid, "DRAFT", 2));
                    });
            when(phaseRepository.findByContextIdOrderByPhaseIndexAsc(anyString()))
                    .thenReturn(List.of(
                            createPhaseEntity("p1", 0, "Phase A", DIR_A),
                            createPhaseEntity("p2", 1, "Phase B", DIR_B)));

            CreateCrossProjectTaskForm form = buildCreateForm("Test Task", DIR_A, DIR_B);

            // Act
            CrossProjectTaskDTO result = service.createTask(USER_ID, TENANT_ID, form);

            // Assert
            assertNotNull(result);
            assertEquals("Test Task", result.getTitle());
            assertEquals(2, result.getTotalPhases());
            assertEquals(2, result.getPhases().size());

            // Verify entity saved
            verify(taskRepository).save(any(CrossProjectTaskEntity.class));
            // 2 phases saved
            verify(phaseRepository, times(2)).save(any(CrossProjectPhaseEntity.class));
        }

        @Test
        void createTask_emptyTitle_throws() {
            CreateCrossProjectTaskForm form = new CreateCrossProjectTaskForm();
            form.setTitle("");
            form.setPhases(List.of(new CreateCrossProjectTaskForm.PhaseForm()));

            assertThrows(IllegalArgumentException.class,
                    () -> service.createTask(USER_ID, TENANT_ID, form));
        }

        @Test
        void createTask_noPhases_throws() {
            CreateCrossProjectTaskForm form = new CreateCrossProjectTaskForm();
            form.setTitle("Test");
            form.setPhases(List.of());

            assertThrows(IllegalArgumentException.class,
                    () -> service.createTask(USER_ID, TENANT_ID, form));
        }

        @Test
        void createTask_withAgent_resolvesDirectory() {
            CodingAgentEntity agent = new CodingAgentEntity();
            agent.setAgentId(AGENT_ID);
            agent.setWorkerId(WORKER_ID);
            agent.setDefaultDirectoryId(DIR_A);
            when(codingAgentService.getAgentEntity(AGENT_ID)).thenReturn(agent);

            // For getTask() enrichment
            when(taskRepository.findByContextIdAndUserId(anyString(), eq(USER_ID)))
                    .thenAnswer(inv -> Optional.of(createTaskEntity(inv.getArgument(0), "DRAFT", 1)));
            when(phaseRepository.findByContextIdOrderByPhaseIndexAsc(anyString()))
                    .thenReturn(List.of(createPhaseEntity("p1", 0, "Phase A", DIR_A)));

            CreateCrossProjectTaskForm form = new CreateCrossProjectTaskForm();
            form.setTitle("Agent Task");
            CreateCrossProjectTaskForm.PhaseForm pf = new CreateCrossProjectTaskForm.PhaseForm();
            pf.setPhaseName("Phase 1");
            pf.setPrompt("Do work");
            pf.setAgentId(AGENT_ID);
            // No directoryId — should resolve from agent.defaultDirectoryId
            form.setPhases(List.of(pf));

            service.createTask(USER_ID, TENANT_ID, form);

            // Verify the saved phase picked up agent's directoryId + workerId
            ArgumentCaptor<CrossProjectPhaseEntity> captor =
                    ArgumentCaptor.forClass(CrossProjectPhaseEntity.class);
            verify(phaseRepository).save(captor.capture());
            assertEquals(DIR_A, captor.getValue().getDirectoryId());
            assertEquals(WORKER_ID, captor.getValue().getWorkerId());
            assertEquals(AGENT_ID, captor.getValue().getAgentId());
        }

        @Test
        void createTask_withDirectDirectory_resolvesWorker() {
            when(directoryRepository.findByDirectoryIdAndUserId(DIR_A, USER_ID))
                    .thenReturn(Optional.of(createDirectory(DIR_A, WORKER_ID)));

            when(taskRepository.findByContextIdAndUserId(anyString(), eq(USER_ID)))
                    .thenAnswer(inv -> Optional.of(createTaskEntity(inv.getArgument(0), "DRAFT", 1)));
            when(phaseRepository.findByContextIdOrderByPhaseIndexAsc(anyString()))
                    .thenReturn(List.of(createPhaseEntity("p1", 0, "Phase A", DIR_A)));

            CreateCrossProjectTaskForm form = new CreateCrossProjectTaskForm();
            form.setTitle("Direct Dir Task");
            CreateCrossProjectTaskForm.PhaseForm pf = new CreateCrossProjectTaskForm.PhaseForm();
            pf.setPhaseName("Phase 1");
            pf.setPrompt("Do work");
            pf.setDirectoryId(DIR_A);
            // No agentId
            form.setPhases(List.of(pf));

            service.createTask(USER_ID, TENANT_ID, form);

            ArgumentCaptor<CrossProjectPhaseEntity> captor =
                    ArgumentCaptor.forClass(CrossProjectPhaseEntity.class);
            verify(phaseRepository).save(captor.capture());
            assertEquals(DIR_A, captor.getValue().getDirectoryId());
            assertEquals(WORKER_ID, captor.getValue().getWorkerId());
        }
    }

    // ========== Start ==========

    @Nested
    class StartTests {

        @Test
        void startTask_success() {
            CrossProjectTaskEntity task = createTaskEntity(CONTEXT_ID, "DRAFT", 2);
            when(taskRepository.findByContextIdAndUserId(CONTEXT_ID, USER_ID))
                    .thenReturn(Optional.of(task));

            CrossProjectPhaseEntity phase0 = createPhaseEntity("p0", 0, "Phase 0", DIR_A);
            phase0.setContextId(CONTEXT_ID);
            phase0.setWorkerId(WORKER_ID);
            when(phaseRepository.findByContextIdAndPhaseIndex(CONTEXT_ID, 0))
                    .thenReturn(Optional.of(phase0));

            // Directory lookup for startPhase
            when(directoryRepository.findByDirectoryId(DIR_A))
                    .thenReturn(Optional.of(createDirectory(DIR_A, WORKER_ID)));

            // Worktree creation
            WorkingDirectoryDTO worktreeDTO = WorkingDirectoryDTO.builder()
                    .directoryId("wt-dir-1")
                    .gitBranch("cross-project/ctx-12345678/phase-0")
                    .build();
            when(directoryService.createWorktree(eq(USER_ID), eq(TENANT_ID), eq(DIR_A), anyString()))
                    .thenReturn(worktreeDTO);

            // ClaudeTask creation
            TaskDTO claudeTask = TaskDTO.builder()
                    .taskId("ct-001")
                    .sessionId("sess-001")
                    .build();
            when(claudeTaskService.createTask(eq(USER_ID), eq(TENANT_ID), any()))
                    .thenReturn(claudeTask);

            // For getTask() enrichment
            when(phaseRepository.findByContextIdOrderByPhaseIndexAsc(CONTEXT_ID))
                    .thenReturn(List.of(phase0));

            CrossProjectTaskDTO result = service.startTask(USER_ID, CONTEXT_ID);

            assertNotNull(result);

            // Verify task status updated
            ArgumentCaptor<CrossProjectTaskEntity> taskCaptor =
                    ArgumentCaptor.forClass(CrossProjectTaskEntity.class);
            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            CrossProjectTaskEntity savedTask = taskCaptor.getAllValues().stream()
                    .filter(t -> "RUNNING".equals(t.getStatus()))
                    .findFirst().orElse(null);
            assertNotNull(savedTask);
            assertEquals(0, savedTask.getCurrentPhaseIndex());

            // Verify worktree was created
            verify(directoryService).createWorktree(eq(USER_ID), eq(TENANT_ID), eq(DIR_A), anyString());
            // Verify Claude task was created
            verify(claudeTaskService).createTask(eq(USER_ID), eq(TENANT_ID), any());
        }

        @Test
        void startTask_notDraft_throws() {
            CrossProjectTaskEntity task = createTaskEntity(CONTEXT_ID, "RUNNING", 2);
            when(taskRepository.findByContextIdAndUserId(CONTEXT_ID, USER_ID))
                    .thenReturn(Optional.of(task));

            assertThrows(IllegalStateException.class,
                    () -> service.startTask(USER_ID, CONTEXT_ID));
        }
    }

    // ========== Advance ==========

    @Nested
    class AdvanceTests {

        @Test
        void advancePhase_success() {
            CrossProjectTaskEntity task = createTaskEntity(CONTEXT_ID, "PAUSED", 2);
            task.setCurrentPhaseIndex(0);
            when(taskRepository.findByContextIdAndUserId(CONTEXT_ID, USER_ID))
                    .thenReturn(Optional.of(task));

            CrossProjectPhaseEntity phase0 = createPhaseEntity("p0", 0, "Phase 0", DIR_A);
            phase0.setContextId(CONTEXT_ID);
            phase0.setStatus("AWAITING_REVIEW");
            phase0.setHandoffArtifact("API changed: POST /api/refund");
            when(phaseRepository.findByContextIdAndPhaseIndex(CONTEXT_ID, 0))
                    .thenReturn(Optional.of(phase0));

            CrossProjectPhaseEntity phase1 = createPhaseEntity("p1", 1, "Phase 1", DIR_B);
            phase1.setContextId(CONTEXT_ID);
            phase1.setWorkerId(WORKER_ID);
            when(phaseRepository.findByContextIdAndPhaseIndex(CONTEXT_ID, 1))
                    .thenReturn(Optional.of(phase1));

            // Directory for startPhase
            when(directoryRepository.findByDirectoryId(DIR_B))
                    .thenReturn(Optional.of(createDirectory(DIR_B, WORKER_ID)));

            // Worktree
            WorkingDirectoryDTO worktreeDTO = WorkingDirectoryDTO.builder()
                    .directoryId("wt-dir-2")
                    .gitBranch("cross-project/ctx-12345678/phase-1")
                    .build();
            when(directoryService.createWorktree(eq(USER_ID), eq(TENANT_ID), eq(DIR_B), anyString()))
                    .thenReturn(worktreeDTO);

            // ClaudeTask
            TaskDTO claudeTask = TaskDTO.builder()
                    .taskId("ct-002")
                    .sessionId("sess-002")
                    .build();
            when(claudeTaskService.createTask(eq(USER_ID), eq(TENANT_ID), any()))
                    .thenReturn(claudeTask);

            // For getTask() enrichment
            when(phaseRepository.findByContextIdOrderByPhaseIndexAsc(CONTEXT_ID))
                    .thenReturn(List.of(phase0, phase1));

            CrossProjectTaskDTO result = service.advancePhase(USER_ID, TENANT_ID, CONTEXT_ID, null);

            assertNotNull(result);

            // Verify phase 0 completed
            assertEquals("COMPLETED", phase0.getStatus());
            assertNotNull(phase0.getCompletedAt());

            // Verify next phase was started
            verify(directoryService).createWorktree(eq(USER_ID), eq(TENANT_ID), eq(DIR_B), anyString());
            verify(claudeTaskService).createTask(eq(USER_ID), eq(TENANT_ID), any());
        }

        @Test
        void advancePhase_lastPhase_completesTask() {
            CrossProjectTaskEntity task = createTaskEntity(CONTEXT_ID, "PAUSED", 1);
            task.setCurrentPhaseIndex(0);
            when(taskRepository.findByContextIdAndUserId(CONTEXT_ID, USER_ID))
                    .thenReturn(Optional.of(task));

            CrossProjectPhaseEntity phase0 = createPhaseEntity("p0", 0, "Phase 0", DIR_A);
            phase0.setContextId(CONTEXT_ID);
            phase0.setStatus("AWAITING_REVIEW");
            when(phaseRepository.findByContextIdAndPhaseIndex(CONTEXT_ID, 0))
                    .thenReturn(Optional.of(phase0));

            // For aggregateCost
            when(phaseRepository.findByContextIdOrderByPhaseIndexAsc(CONTEXT_ID))
                    .thenReturn(List.of(phase0));

            CrossProjectTaskDTO result = service.advancePhase(USER_ID, TENANT_ID, CONTEXT_ID, null);

            assertNotNull(result);

            // Task should be COMPLETED
            assertEquals("COMPLETED", task.getStatus());
            assertNotNull(task.getCompletedAt());

            // No more worktree/task creation
            verify(directoryService, never()).createWorktree(any(), any(), any(), any());
            verify(claudeTaskService, never()).createTask(any(), any(), any());
        }

        @Test
        void advancePhase_withHandoffOverride() {
            CrossProjectTaskEntity task = createTaskEntity(CONTEXT_ID, "PAUSED", 1);
            task.setCurrentPhaseIndex(0);
            when(taskRepository.findByContextIdAndUserId(CONTEXT_ID, USER_ID))
                    .thenReturn(Optional.of(task));

            CrossProjectPhaseEntity phase0 = createPhaseEntity("p0", 0, "Phase 0", DIR_A);
            phase0.setContextId(CONTEXT_ID);
            phase0.setStatus("AWAITING_REVIEW");
            phase0.setHandoffArtifact("old handoff");
            when(phaseRepository.findByContextIdAndPhaseIndex(CONTEXT_ID, 0))
                    .thenReturn(Optional.of(phase0));

            when(phaseRepository.findByContextIdOrderByPhaseIndexAsc(CONTEXT_ID))
                    .thenReturn(List.of(phase0));

            service.advancePhase(USER_ID, TENANT_ID, CONTEXT_ID, "new handoff override");

            assertEquals("new handoff override", phase0.getHandoffArtifact());
        }
    }

    // ========== Event Callback ==========

    @Nested
    class EventCallbackTests {

        @Test
        void onTaskCompleted_matchingPhase_setsAwaitingReview() {
            String claudeTaskId = "ct-phase-001";
            CrossProjectPhaseEntity phase = createPhaseEntity("p0", 0, "Phase 0", DIR_A);
            phase.setContextId(CONTEXT_ID);
            phase.setStatus("RUNNING");
            phase.setClaudeTaskId(claudeTaskId);
            when(phaseRepository.findByClaudeTaskId(claudeTaskId))
                    .thenReturn(Optional.of(phase));

            CrossProjectTaskEntity task = createTaskEntity(CONTEXT_ID, "RUNNING", 2);
            task.setInitialSessionId("init-session");
            when(taskRepository.findByContextId(CONTEXT_ID))
                    .thenReturn(Optional.of(task));

            // ClaudeTask enrichment
            ClaudeTaskEntity claudeTaskEntity = new ClaudeTaskEntity();
            claudeTaskEntity.setClaudeSessionId("cs-001");
            claudeTaskEntity.setCostUsd(new BigDecimal("0.1234"));
            claudeTaskEntity.setDurationMs(60000L);
            when(claudeTaskService.getTaskEntity(claudeTaskId)).thenReturn(claudeTaskEntity);

            TaskCompletionEvent event = TaskCompletionEvent.builder()
                    .externalTaskId(claudeTaskId)
                    .status("COMPLETED")
                    .build();

            service.onTaskCompleted(event);

            assertEquals("AWAITING_REVIEW", phase.getStatus());
            assertEquals("cs-001", phase.getClaudeSessionId());
            assertEquals(new BigDecimal("0.1234"), phase.getCostUsd());
            assertEquals("PAUSED", task.getStatus());

            verify(phaseRepository).save(phase);
            verify(taskRepository).save(task);
            // SSE notification sent (use Object.class to match the publishEvent(Object) overload)
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        void onTaskCompleted_failedTask_setsPhaseAndTaskFailed() {
            String claudeTaskId = "ct-phase-002";
            CrossProjectPhaseEntity phase = createPhaseEntity("p0", 0, "Phase 0", DIR_A);
            phase.setContextId(CONTEXT_ID);
            phase.setStatus("RUNNING");
            when(phaseRepository.findByClaudeTaskId(claudeTaskId))
                    .thenReturn(Optional.of(phase));

            CrossProjectTaskEntity task = createTaskEntity(CONTEXT_ID, "RUNNING", 2);
            when(taskRepository.findByContextId(CONTEXT_ID))
                    .thenReturn(Optional.of(task));

            TaskCompletionEvent event = TaskCompletionEvent.builder()
                    .externalTaskId(claudeTaskId)
                    .status("FAILED")
                    .build();

            service.onTaskCompleted(event);

            assertEquals("FAILED", phase.getStatus());
            assertEquals("FAILED", task.getStatus());
            verify(phaseRepository).save(phase);
            verify(taskRepository).save(task);
        }

        @Test
        void onTaskCompleted_nonCrossProjectTask_ignored() {
            when(phaseRepository.findByClaudeTaskId("unrelated-task"))
                    .thenReturn(Optional.empty());

            TaskCompletionEvent event = TaskCompletionEvent.builder()
                    .externalTaskId("unrelated-task")
                    .status("COMPLETED")
                    .build();

            service.onTaskCompleted(event);

            verify(taskRepository, never()).save(any());
            verify(phaseRepository, never()).save(any());
        }

        @Test
        void onTaskCompleted_nullExternalTaskId_ignored() {
            TaskCompletionEvent event = TaskCompletionEvent.builder()
                    .externalTaskId(null)
                    .status("COMPLETED")
                    .build();

            service.onTaskCompleted(event);

            verify(phaseRepository, never()).findByClaudeTaskId(any());
        }
    }

    // ========== Cancel ==========

    @Test
    void cancelTask_success() {
        CrossProjectTaskEntity task = createTaskEntity(CONTEXT_ID, "RUNNING", 2);
        when(taskRepository.findByContextIdAndUserId(CONTEXT_ID, USER_ID))
                .thenReturn(Optional.of(task));

        CrossProjectPhaseEntity phase0 = createPhaseEntity("p0", 0, "Phase 0", DIR_A);
        phase0.setContextId(CONTEXT_ID);
        phase0.setStatus("COMPLETED");
        CrossProjectPhaseEntity phase1 = createPhaseEntity("p1", 1, "Phase 1", DIR_B);
        phase1.setContextId(CONTEXT_ID);
        phase1.setStatus("RUNNING");
        when(phaseRepository.findByContextIdOrderByPhaseIndexAsc(CONTEXT_ID))
                .thenReturn(List.of(phase0, phase1));

        // For getTask() enrichment
        when(taskRepository.findByContextIdAndUserId(CONTEXT_ID, USER_ID))
                .thenReturn(Optional.of(task));

        CrossProjectTaskDTO result = service.cancelTask(USER_ID, CONTEXT_ID);

        assertNotNull(result);
        assertEquals("CANCELLED", task.getStatus());
        // Phase 0 was COMPLETED, should stay COMPLETED
        assertEquals("COMPLETED", phase0.getStatus());
        // Phase 1 was RUNNING, should become SKIPPED
        assertEquals("SKIPPED", phase1.getStatus());
    }

    @Test
    void cancelTask_alreadyCompleted_throws() {
        CrossProjectTaskEntity task = createTaskEntity(CONTEXT_ID, "COMPLETED", 2);
        when(taskRepository.findByContextIdAndUserId(CONTEXT_ID, USER_ID))
                .thenReturn(Optional.of(task));

        assertThrows(IllegalStateException.class,
                () -> service.cancelTask(USER_ID, CONTEXT_ID));
    }

    // ========== Update Handoff ==========

    @Test
    void updateHandoff_success() {
        CrossProjectTaskEntity task = createTaskEntity(CONTEXT_ID, "PAUSED", 2);
        when(taskRepository.findByContextIdAndUserId(CONTEXT_ID, USER_ID))
                .thenReturn(Optional.of(task));

        CrossProjectPhaseEntity phase = createPhaseEntity("p0", 0, "Phase 0", DIR_A);
        phase.setContextId(CONTEXT_ID);
        when(phaseRepository.findByPhaseId("p0")).thenReturn(Optional.of(phase));

        CrossProjectPhaseDTO result = service.updateHandoff(USER_ID, CONTEXT_ID, "p0", "New handoff data");

        assertNotNull(result);
        assertEquals("New handoff data", phase.getHandoffArtifact());
        verify(phaseRepository).save(phase);
    }

    @Test
    void updateHandoff_phaseMismatch_throws() {
        CrossProjectTaskEntity task = createTaskEntity(CONTEXT_ID, "PAUSED", 2);
        when(taskRepository.findByContextIdAndUserId(CONTEXT_ID, USER_ID))
                .thenReturn(Optional.of(task));

        CrossProjectPhaseEntity phase = createPhaseEntity("p0", 0, "Phase 0", DIR_A);
        phase.setContextId("other-context"); // Mismatched context
        when(phaseRepository.findByPhaseId("p0")).thenReturn(Optional.of(phase));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateHandoff(USER_ID, CONTEXT_ID, "p0", "data"));
    }

    // ========== Helpers ==========

    private CreateCrossProjectTaskForm buildCreateForm(String title, String... directoryIds) {
        CreateCrossProjectTaskForm form = new CreateCrossProjectTaskForm();
        form.setTitle(title);
        form.setDescription("Test description");
        form.setInitialSessionId("init-session");
        form.setInitialDirectoryId("init-dir");
        List<CreateCrossProjectTaskForm.PhaseForm> phases = new java.util.ArrayList<>();
        for (int i = 0; i < directoryIds.length; i++) {
            CreateCrossProjectTaskForm.PhaseForm pf = new CreateCrossProjectTaskForm.PhaseForm();
            pf.setPhaseName("Phase " + i);
            pf.setPrompt("Do work on phase " + i);
            pf.setDirectoryId(directoryIds[i]);
            phases.add(pf);
        }
        form.setPhases(phases);
        return form;
    }

    private CrossProjectTaskEntity createTaskEntity(String contextId, String status, int totalPhases) {
        CrossProjectTaskEntity entity = new CrossProjectTaskEntity();
        entity.setContextId(contextId);
        entity.setUserId(USER_ID);
        entity.setTenantId(TENANT_ID);
        entity.setTitle("Test Task");
        entity.setStatus(status);
        entity.setTotalPhases(totalPhases);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private CrossProjectPhaseEntity createPhaseEntity(String phaseId, int index,
                                                       String name, String directoryId) {
        CrossProjectPhaseEntity entity = new CrossProjectPhaseEntity();
        entity.setPhaseId(phaseId);
        entity.setPhaseIndex(index);
        entity.setPhaseName(name);
        entity.setPrompt("Do work for " + name);
        entity.setDirectoryId(directoryId);
        entity.setWorkerId(WORKER_ID);
        entity.setStatus("PENDING");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private WorkingDirectoryEntity createDirectory(String directoryId, String workerId) {
        WorkingDirectoryEntity dir = new WorkingDirectoryEntity();
        dir.setDirectoryId(directoryId);
        dir.setWorkerId(workerId);
        dir.setUserId(USER_ID);
        dir.setTenantId(TENANT_ID);
        dir.setProjectName("project-" + directoryId);
        dir.setPath("/home/user/" + directoryId);
        dir.setDirectoryType("STANDARD");
        dir.setGitBranch("main");
        return dir;
    }
}
