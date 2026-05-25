package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.common.form.SharedAskForm;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.AgentConsultationRepository;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.service.SharingKeyService;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SharedAskControllerTest {

    @Mock
    private SharingKeyService sharingKeyService;
    @Mock
    private UnifiedAgentResolver agentResolver;
    @Mock
    private AgentConsultationRepository consultationRepository;
    @Mock
    private A2aAgent agent;

    @Test
    void ask_usesUnifiedResolverAndSendsSharedMetadata() {
        SharedAskController controller = new SharedAskController(
                sharingKeyService, agentResolver, consultationRepository, defaultPipeline());
        SharingKeyEntity keyEntity = new SharingKeyEntity();
        keyEntity.setId("key-1");
        keyEntity.setAgentId("agent-1");
        keyEntity.setOwnerUserId("owner-1");
        keyEntity.setMaxTurns(3);
        keyEntity.setSystemPrompt("default system");

        SharedAskForm form = new SharedAskForm();
        form.setQuestion("hello");
        form.setContextId("ctx-1");
        form.setContextAlias("alias-1");
        form.setFirstMsg("first");

        when(sharingKeyService.validateAndConsume("shk-1")).thenReturn(keyEntity);
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-1").name("Agent 1").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        RX<A2aTask> result = controller.ask("shk-1", form);

        assertNotNull(result.getData());
        assertEquals("task-1", result.getData().getId());

        ArgumentCaptor<A2aMessage> messageCaptor = ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(messageCaptor.capture());
        A2aMessage message = messageCaptor.getValue();
        assertEquals("ctx-1", message.getContextId());
        assertEquals("alias-1", message.getContextAlias());
        assertEquals(3, message.getMetadata().get("maxTurns"));
        assertEquals("default system", message.getMetadata().get("systemPrompt"));
        assertEquals("first", message.getMetadata().get("firstMsg"));
    }

    @Test
    void ask_returnsFailWhenAgentUnavailable() {
        SharedAskController controller = new SharedAskController(
                sharingKeyService, agentResolver, consultationRepository, defaultPipeline());
        SharingKeyEntity keyEntity = new SharingKeyEntity();
        keyEntity.setAgentId("agent-1");
        keyEntity.setOwnerUserId("owner-1");

        SharedAskForm form = new SharedAskForm();
        form.setQuestion("hello");

        when(sharingKeyService.validateAndConsume("shk-1")).thenReturn(keyEntity);
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.empty());

        RX<A2aTask> result = controller.ask("shk-1", form);

        assertNull(result.getData());
        assertEquals("Shared agent not available", result.getMsg());
    }

    private AgentSubmitPipeline defaultPipeline() {
        return request -> AgentTaskSubmitResult.of(agent.sendTask(request.getMessage()));
    }
}
