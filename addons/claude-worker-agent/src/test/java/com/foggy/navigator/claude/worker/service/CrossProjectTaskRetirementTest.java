package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.claude.worker.config.CrossProjectTaskProperties;
import com.foggy.navigator.claude.worker.model.dto.CrossProjectTaskDTO;
import com.foggy.navigator.claude.worker.model.entity.CrossProjectTaskEntity;
import com.foggy.navigator.claude.worker.model.form.CreateCrossProjectTaskForm;
import com.foggy.navigator.claude.worker.repository.CrossProjectPhaseRepository;
import com.foggy.navigator.claude.worker.repository.CrossProjectTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CrossProjectTaskRetirementTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String CONTEXT_ID = "ctx-retired";

    private CrossProjectTaskRepository taskRepository;
    private CrossProjectPhaseRepository phaseRepository;
    private ClaudeTaskService claudeTaskService;
    private CodingAgentService codingAgentService;
    private WorkingDirectoryService directoryService;
    private WorkingDirectoryRepository directoryRepository;
    private ApplicationEventPublisher eventPublisher;
    private CrossProjectTaskService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(CrossProjectTaskRepository.class);
        phaseRepository = mock(CrossProjectPhaseRepository.class);
        claudeTaskService = mock(ClaudeTaskService.class);
        codingAgentService = mock(CodingAgentService.class);
        directoryService = mock(WorkingDirectoryService.class);
        directoryRepository = mock(WorkingDirectoryRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        CrossProjectTaskProperties properties = new CrossProjectTaskProperties();
        service = new CrossProjectTaskService(
                taskRepository,
                phaseRepository,
                claudeTaskService,
                codingAgentService,
                directoryService,
                directoryRepository,
                eventPublisher,
                new CrossProjectMutationGate(properties));
    }

    @Test
    void mutationGateDefaultsDisabledAndSupportsExplicitRollbackSwitch() {
        CrossProjectTaskProperties properties = new CrossProjectTaskProperties();
        CrossProjectMutationGate gate = new CrossProjectMutationGate(properties);

        assertFalse(gate.isEnabled());
        CrossProjectMutationRetiredException retired = assertThrows(
                CrossProjectMutationRetiredException.class,
                gate::requireEnabled);
        assertEquals(CrossProjectMutationRetiredException.REASON_CODE, retired.getMessage());

        properties.setMutationsEnabled(true);
        assertDoesNotThrow(gate::requireEnabled);
    }

    @Test
    void allSixServiceMutationsFailBeforeRepositoryIdWorktreeProviderOrEventEffects() {
        assertRetired(() -> service.createTask(USER_ID, TENANT_ID, new CreateCrossProjectTaskForm()));
        assertRetired(() -> service.startTask(USER_ID, CONTEXT_ID));
        assertRetired(() -> service.triggerReview(USER_ID, TENANT_ID, CONTEXT_ID));
        assertRetired(() -> service.updateHandoff(USER_ID, CONTEXT_ID, "phase-1", "handoff"));
        assertRetired(() -> service.advancePhase(USER_ID, TENANT_ID, CONTEXT_ID, "handoff"));
        assertRetired(() -> service.cancelTask(USER_ID, CONTEXT_ID));

        verifyNoInteractions(
                taskRepository,
                phaseRepository,
                claudeTaskService,
                codingAgentService,
                directoryService,
                directoryRepository,
                eventPublisher);
    }

    @Test
    void completionEventIsSilentBeforeAnyRepositoryReadWhenMutationsAreDisabled() {
        service.onTaskCompleted(TaskCompletionEvent.builder()
                .externalTaskId("claude-task-existing")
                .status("COMPLETED")
                .build());

        verifyNoInteractions(
                taskRepository,
                phaseRepository,
                claudeTaskService,
                codingAgentService,
                directoryService,
                directoryRepository,
                eventPublisher);
    }

    @Test
    void getRemainsReadOnlyWhenMutationsAreDisabled() {
        CrossProjectTaskEntity task = taskEntity();
        when(taskRepository.findByContextIdAndUserId(CONTEXT_ID, USER_ID))
                .thenReturn(Optional.of(task));
        when(phaseRepository.findByContextIdOrderByPhaseIndexAsc(CONTEXT_ID))
                .thenReturn(List.of());

        CrossProjectTaskDTO result = service.getTask(USER_ID, CONTEXT_ID);

        assertEquals(CONTEXT_ID, result.getContextId());
        verify(taskRepository).findByContextIdAndUserId(CONTEXT_ID, USER_ID);
        verify(phaseRepository).findByContextIdOrderByPhaseIndexAsc(CONTEXT_ID);
        verify(taskRepository, never()).save(any());
        verify(phaseRepository, never()).save(any());
        verifyNoMoreInteractions(taskRepository, phaseRepository);
        verifyNoInteractions(
                claudeTaskService,
                codingAgentService,
                directoryService,
                directoryRepository,
                eventPublisher);
    }

    @Test
    void listRemainsReadOnlyWhenMutationsAreDisabled() {
        CrossProjectTaskEntity task = taskEntity();
        when(taskRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, Pageable.ofSize(10)))
                .thenReturn(new PageImpl<>(List.of(task)));
        when(phaseRepository.findByContextIdOrderByPhaseIndexAsc(CONTEXT_ID))
                .thenReturn(List.of());

        Page<CrossProjectTaskDTO> result = service.listTasks(USER_ID, 0, 10);

        assertEquals(1, result.getTotalElements());
        verify(taskRepository).findByUserIdOrderByCreatedAtDesc(USER_ID, Pageable.ofSize(10));
        verify(phaseRepository).findByContextIdOrderByPhaseIndexAsc(CONTEXT_ID);
        verify(taskRepository, never()).save(any());
        verify(phaseRepository, never()).save(any());
        verifyNoMoreInteractions(taskRepository, phaseRepository);
        verifyNoInteractions(
                claudeTaskService,
                codingAgentService,
                directoryService,
                directoryRepository,
                eventPublisher);
    }

    private void assertRetired(Runnable mutation) {
        CrossProjectMutationRetiredException retired = assertThrows(
                CrossProjectMutationRetiredException.class,
                mutation::run);
        assertEquals(CrossProjectMutationRetiredException.REASON_CODE, retired.getMessage());
    }

    private CrossProjectTaskEntity taskEntity() {
        CrossProjectTaskEntity task = new CrossProjectTaskEntity();
        task.setContextId(CONTEXT_ID);
        task.setUserId(USER_ID);
        task.setTenantId(TENANT_ID);
        task.setTitle("Existing task");
        task.setStatus("PAUSED");
        task.setTotalPhases(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }
}
