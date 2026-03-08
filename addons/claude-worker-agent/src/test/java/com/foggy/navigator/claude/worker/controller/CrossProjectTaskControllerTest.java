package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.model.dto.CrossProjectPhaseDTO;
import com.foggy.navigator.claude.worker.model.dto.CrossProjectTaskDTO;
import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.form.AdvancePhaseForm;
import com.foggy.navigator.claude.worker.model.form.CreateCrossProjectTaskForm;
import com.foggy.navigator.claude.worker.service.CrossProjectTaskService;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrossProjectTaskControllerTest {

    private CrossProjectTaskService taskService;
    private CrossProjectTaskController controller;

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String CONTEXT_ID = "ctx-12345678";

    @BeforeEach
    void setUp() {
        taskService = mock(CrossProjectTaskService.class);
        controller = new CrossProjectTaskController(taskService);
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(USER_ID)
                .username("testuser")
                .tenantId(TENANT_ID)
                .build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createTask_returnsOk() {
        CreateCrossProjectTaskForm form = new CreateCrossProjectTaskForm();
        form.setTitle("Test Task");
        CrossProjectTaskDTO dto = buildTaskDTO(CONTEXT_ID, "DRAFT");
        when(taskService.createTask(USER_ID, TENANT_ID, form)).thenReturn(dto);

        RX<CrossProjectTaskDTO> result = controller.createTask(form);

        assertEquals(200, result.getCode());
        assertEquals(CONTEXT_ID, result.getData().getContextId());
        assertEquals("DRAFT", result.getData().getStatus());
    }

    @Test
    void startTask_returnsOk() {
        CrossProjectTaskDTO dto = buildTaskDTO(CONTEXT_ID, "RUNNING");
        when(taskService.startTask(USER_ID, CONTEXT_ID)).thenReturn(dto);

        RX<CrossProjectTaskDTO> result = controller.startTask(CONTEXT_ID);

        assertEquals(200, result.getCode());
        assertEquals("RUNNING", result.getData().getStatus());
    }

    @Test
    void getTask_returnsOk() {
        CrossProjectTaskDTO dto = buildTaskDTO(CONTEXT_ID, "PAUSED");
        when(taskService.getTask(USER_ID, CONTEXT_ID)).thenReturn(dto);

        RX<CrossProjectTaskDTO> result = controller.getTask(CONTEXT_ID);

        assertEquals(200, result.getCode());
        assertEquals("PAUSED", result.getData().getStatus());
    }

    @Test
    void listTasks_returnsPage() {
        CrossProjectTaskDTO dto = buildTaskDTO(CONTEXT_ID, "RUNNING");
        Page<CrossProjectTaskDTO> page = new PageImpl<>(List.of(dto));
        when(taskService.listTasks(USER_ID, 0, 20)).thenReturn(page);

        RX<Page<CrossProjectTaskDTO>> result = controller.listTasks(0, 20);

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().getTotalElements());
    }

    @Test
    void triggerReview_returnsResumedTask() {
        TaskDTO resumed = TaskDTO.builder()
                .taskId("ct-review-001")
                .sessionId("sess-init")
                .build();
        when(taskService.triggerReview(USER_ID, TENANT_ID, CONTEXT_ID)).thenReturn(resumed);

        RX<TaskDTO> result = controller.triggerReview(CONTEXT_ID);

        assertEquals(200, result.getCode());
        assertEquals("ct-review-001", result.getData().getTaskId());
    }

    @Test
    void updateHandoff_returnsPhaseDTO() {
        CrossProjectPhaseDTO phaseDTO = CrossProjectPhaseDTO.builder()
                .phaseId("p0")
                .handoffArtifact("Updated handoff")
                .build();
        when(taskService.updateHandoff(USER_ID, CONTEXT_ID, "p0", "Updated handoff"))
                .thenReturn(phaseDTO);

        RX<CrossProjectPhaseDTO> result = controller.updateHandoff(
                CONTEXT_ID, "p0", Map.of("handoffArtifact", "Updated handoff"));

        assertEquals(200, result.getCode());
        assertEquals("Updated handoff", result.getData().getHandoffArtifact());
    }

    @Test
    void advancePhase_returnsOk() {
        CrossProjectTaskDTO dto = buildTaskDTO(CONTEXT_ID, "RUNNING");
        AdvancePhaseForm form = new AdvancePhaseForm();
        form.setHandoffArtifact("override handoff");
        when(taskService.advancePhase(USER_ID, TENANT_ID, CONTEXT_ID, "override handoff"))
                .thenReturn(dto);

        RX<CrossProjectTaskDTO> result = controller.advancePhase(CONTEXT_ID, form);

        assertEquals(200, result.getCode());
        verify(taskService).advancePhase(USER_ID, TENANT_ID, CONTEXT_ID, "override handoff");
    }

    @Test
    void cancelTask_returnsOk() {
        CrossProjectTaskDTO dto = buildTaskDTO(CONTEXT_ID, "CANCELLED");
        when(taskService.cancelTask(USER_ID, CONTEXT_ID)).thenReturn(dto);

        RX<CrossProjectTaskDTO> result = controller.cancelTask(CONTEXT_ID);

        assertEquals(200, result.getCode());
        assertEquals("CANCELLED", result.getData().getStatus());
    }

    private CrossProjectTaskDTO buildTaskDTO(String contextId, String status) {
        return CrossProjectTaskDTO.builder()
                .contextId(contextId)
                .title("Test Task")
                .status(status)
                .totalPhases(2)
                .phases(List.of())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
