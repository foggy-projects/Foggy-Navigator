//package com.foggy.navigator.session.service;
//
//import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
//import com.foggy.navigator.agent.framework.protocol.AgentMessage;
//import com.foggy.navigator.agent.framework.protocol.MessageType;
//import com.foggy.navigator.common.entity.AgentTaskEntity;
//import com.foggy.navigator.session.repository.AgentTaskRepository;
//import com.foggy.navigator.session.sse.SseSessionEmitter;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.mockito.ArgumentCaptor;
//
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.Mockito.*;
//
//class AgentTaskServiceTest {
//
//    private AgentTaskRepository repository;
//    private SseSessionEmitter sseEmitter;
//    private AgentTaskService service;
//
//    @BeforeEach
//    void setUp() {
//        repository = mock(AgentTaskRepository.class);
//        sseEmitter = mock(SseSessionEmitter.class);
//        service = new AgentTaskService(repository, sseEmitter);
//    }
//
//    // ========== createTask ==========
//
//    @Test
//    void createTask_savesEntityAndReturnsTaskId() {
//        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
//
//        String taskId = service.createTask("parent-1", "user-1", "tutor-agent",
//                "coding-agent", "CODING", "Write tests", "child-1", "ext-1");
//
//        assertNotNull(taskId);
//        assertEquals(12, taskId.length());
//
//        ArgumentCaptor<AgentTaskEntity> captor = ArgumentCaptor.forClass(AgentTaskEntity.class);
//        verify(repository).save(captor.capture());
//        AgentTaskEntity saved = captor.getValue();
//        assertEquals("parent-1", saved.getParentSessionId());
//        assertEquals("user-1", saved.getUserId());
//        assertEquals("PENDING", saved.getStatus());
//        assertEquals("Write tests", saved.getPrompt());
//        assertEquals("ext-1", saved.getExternalTaskId());
//    }
//
//    // ========== listTasksBySession ==========
//
//    @Test
//    void listTasksBySession_returnsMappedTasks() {
//        AgentTaskEntity entity = createEntity("task-1", "COMPLETED");
//        when(repository.findByParentSessionId("parent-1")).thenReturn(List.of(entity));
//
//        List<Map<String, Object>> result = service.listTasksBySession("parent-1");
//
//        assertEquals(1, result.size());
//        assertEquals("task-1", result.get(0).get("taskId"));
//        assertEquals("COMPLETED", result.get(0).get("status"));
//    }
//
//    @Test
//    void listTasksBySession_emptyList() {
//        when(repository.findByParentSessionId("parent-1")).thenReturn(List.of());
//        List<Map<String, Object>> result = service.listTasksBySession("parent-1");
//        assertTrue(result.isEmpty());
//    }
//
//    // ========== completeTask + SSE notification ==========
//
//    @Test
//    void completeTask_updatesEntityAndSendsSSE() {
//        AgentTaskEntity entity = createEntity("task-1", "RUNNING");
//        when(repository.findByTaskId("task-1")).thenReturn(Optional.of(entity));
//
//        service.completeTask("task-1", "COMPLETED", "All tests passed");
//
//        assertEquals("COMPLETED", entity.getStatus());
//        assertEquals("All tests passed", entity.getResultSummary());
//        assertNotNull(entity.getCompletedAt());
//        verify(repository).save(entity);
//
//        // Verify SSE notification
//        ArgumentCaptor<AgentMessage> sseCaptor = ArgumentCaptor.forClass(AgentMessage.class);
//        verify(sseEmitter).sendEvent(eq("parent-1"), sseCaptor.capture());
//        AgentMessage msg = sseCaptor.getValue();
//        assertEquals(MessageType.TASK_COMPLETED, msg.getType());
//        assertEquals("parent-1", msg.getSessionId());
//        assertEquals("tutor-agent", msg.getAgentId());
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> payload = (Map<String, Object>) msg.getPayload();
//        assertEquals("task-1", payload.get("taskId"));
//        assertEquals("COMPLETED", payload.get("status"));
//        assertEquals("All tests passed", payload.get("resultSummary"));
//    }
//
//    @Test
//    void completeTask_notFound_doesNothing() {
//        when(repository.findByTaskId("missing")).thenReturn(Optional.empty());
//
//        service.completeTask("missing", "COMPLETED", "result");
//
//        verify(repository, never()).save(any());
//        verify(sseEmitter, never()).sendEvent(anyString(), any());
//    }
//
//    // ========== completeByExternalTaskId + SSE notification ==========
//
//    @Test
//    void completeByExternalTaskId_updatesAndNotifies() {
//        AgentTaskEntity entity = createEntity("task-2", "RUNNING");
//        entity.setExternalTaskId("ext-task-1");
//        when(repository.findByExternalTaskIdAndTaskType("ext-task-1", "CLAUDE_WORKER"))
//                .thenReturn(Optional.of(entity));
//
//        service.completeByExternalTaskId("ext-task-1", "CLAUDE_WORKER", "FAILED", "Timeout");
//
//        assertEquals("FAILED", entity.getStatus());
//        assertEquals("Timeout", entity.getResultSummary());
//        verify(repository).save(entity);
//
//        // Verify SSE
//        verify(sseEmitter).sendEvent(eq("parent-1"), argThat(msg ->
//                msg.getType() == MessageType.TASK_COMPLETED));
//    }
//
//    @Test
//    void completeByExternalTaskId_notFound_doesNothing() {
//        when(repository.findByExternalTaskIdAndTaskType("x", "Y")).thenReturn(Optional.empty());
//
//        service.completeByExternalTaskId("x", "Y", "COMPLETED", "ok");
//
//        verify(repository, never()).save(any());
//        verify(sseEmitter, never()).sendEvent(anyString(), any());
//    }
//
//    // ========== onTaskCompletion event ==========
//
//    @Test
//    void onTaskCompletion_withExternalTaskId_completesViaExternalId() {
//        AgentTaskEntity entity = createEntity("task-3", "RUNNING");
//        when(repository.findByExternalTaskIdAndTaskType("ext-1", "CLAUDE_WORKER"))
//                .thenReturn(Optional.of(entity));
//
//        TaskCompletionEvent event = TaskCompletionEvent.builder()
//                .externalTaskId("ext-1")
//                .parentSessionId("parent-1")
//                .targetAgentId("claude-worker")
//                .status("COMPLETED")
//                .resultSummary("Done")
//                .build();
//
//        service.onTaskCompletion(event);
//
//        assertEquals("COMPLETED", entity.getStatus());
//        verify(repository).save(entity);
//    }
//
//    @Test
//    void onTaskCompletion_withTaskId_completesViaTaskId() {
//        AgentTaskEntity entity = createEntity("task-4", "RUNNING");
//        when(repository.findByTaskId("task-4")).thenReturn(Optional.of(entity));
//
//        TaskCompletionEvent event = TaskCompletionEvent.builder()
//                .taskId("task-4")
//                .status("FAILED")
//                .resultSummary("Error occurred")
//                .build();
//
//        service.onTaskCompletion(event);
//
//        assertEquals("FAILED", entity.getStatus());
//        assertEquals("Error occurred", entity.getResultSummary());
//    }
//
//    // ========== SSE failure resilience ==========
//
//    @Test
//    void completeTask_sseThrows_doesNotPreventCompletion() {
//        AgentTaskEntity entity = createEntity("task-5", "RUNNING");
//        when(repository.findByTaskId("task-5")).thenReturn(Optional.of(entity));
//        doThrow(new RuntimeException("SSE down")).when(sseEmitter).sendEvent(anyString(), any());
//
//        // Should not throw
//        service.completeTask("task-5", "COMPLETED", "OK");
//
//        assertEquals("COMPLETED", entity.getStatus());
//        verify(repository).save(entity);
//    }
//
//    // ========== Helper ==========
//
//    private AgentTaskEntity createEntity(String taskId, String status) {
//        AgentTaskEntity entity = new AgentTaskEntity();
//        entity.setTaskId(taskId);
//        entity.setParentSessionId("parent-1");
//        entity.setUserId("user-1");
//        entity.setSourceAgentId("tutor-agent");
//        entity.setTargetAgentId("coding-agent");
//        entity.setTaskType("CODING");
//        entity.setStatus(status);
//        entity.setPrompt("Test prompt");
//        return entity;
//    }
//}
