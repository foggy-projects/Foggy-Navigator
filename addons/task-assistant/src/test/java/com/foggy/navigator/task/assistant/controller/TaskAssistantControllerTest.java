package com.foggy.navigator.task.assistant.controller;

import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.task.assistant.spi.TaskAssistantConfig;
import com.foggy.navigator.task.assistant.spi.TaskAssistantFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TaskAssistantController.
 * Tests controller logic only (no Spring context / MockMvc — UserContext is static).
 */
@ExtendWith(MockitoExtension.class)
class TaskAssistantControllerTest {

    @Mock private TaskAssistantFacade assistantFacade;

    private TaskAssistantController controller;

    @BeforeEach
    void setUp() {
        controller = new TaskAssistantController(assistantFacade);
    }

    @Test
    void getAgentCardDelegates() {
        A2aAgentCard card = A2aAgentCard.builder()
                .name("Task Assistant")
                .version("2.0.0")
                .build();
        when(assistantFacade.getAgentCard()).thenReturn(card);

        A2aAgentCard result = controller.getAgentCard();

        assertThat(result.getName()).isEqualTo("Task Assistant");
        assertThat(result.getVersion()).isEqualTo("2.0.0");
    }
}
