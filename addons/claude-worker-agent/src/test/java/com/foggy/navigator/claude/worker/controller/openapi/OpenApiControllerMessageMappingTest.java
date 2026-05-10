package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionMessageDTO;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.claude.worker.service.*;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.service.OpenApiSessionQueryService;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OpenApiControllerMessageMappingTest {

    @Test
    void taskCompletedMessageIsMarkedAsTerminalResult() throws Exception {
        OpenApiController controller = newController();
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId("msg-1");
        entity.setSessionId("session-1");
        entity.setTaskId("task-1");
        entity.setRole("ASSISTANT");
        entity.setContent("done");
        entity.setMetadata("{\"type\":\"TASK_COMPLETED\",\"taskId\":\"task-1\"}");
        entity.setCreatedAt(LocalDateTime.now());

        OpenSessionMessageDTO dto = mapMessage(controller, entity);

        assertEquals("RESULT", dto.getType());
        assertTrue(dto.getTerminal());
        assertEquals("COMPLETED", dto.getTerminalStatus());
        assertEquals("task-1", dto.getTaskId());
    }

    @Test
    void toolCallMessageIsNotTerminal() throws Exception {
        OpenApiController controller = newController();
        SessionMessageEntity entity = new SessionMessageEntity();
        entity.setId("msg-2");
        entity.setSessionId("session-1");
        entity.setTaskId("task-1");
        entity.setRole("ASSISTANT");
        entity.setContent("tms.dataset.listModels");
        entity.setMetadata("{\"type\":\"TOOL_CALL_START\",\"taskId\":\"task-1\"}");
        entity.setCreatedAt(LocalDateTime.now());

        OpenSessionMessageDTO dto = mapMessage(controller, entity);

        assertEquals("TOOL_CALL", dto.getType());
        assertEquals(false, dto.getTerminal());
    }

    private OpenSessionMessageDTO mapMessage(OpenApiController controller, SessionMessageEntity entity)
            throws Exception {
        Method method = OpenApiController.class.getDeclaredMethod(
                "toOpenSessionMessageDTO",
                SessionMessageEntity.class,
                String.class
        );
        method.setAccessible(true);
        return (OpenSessionMessageDTO) method.invoke(controller, entity, "ctx-1");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OpenApiController newController() {
        return new OpenApiController(
                mock(OpenApiProvisioningService.class),
                mock(ClaudeWorkerService.class),
                mock(WorkingDirectoryService.class),
                mock(ClaudeWorkerFacade.class),
                mock(ClaudeWorkerRepository.class),
                mock(CodingAgentRepository.class),
                mock(WorkingDirectoryRepository.class),
                mock(WorkerHealthChecker.class),
                mock(UnifiedAgentResolver.class),
                mock(TaskDispatchFacade.class),
                mock(TaskStateReconciler.class),
                mock(OpenApiSessionQueryService.class),
                new ObjectMapper(),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class)
        );
    }
}
