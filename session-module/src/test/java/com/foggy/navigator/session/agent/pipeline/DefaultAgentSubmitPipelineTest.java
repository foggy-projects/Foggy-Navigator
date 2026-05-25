package com.foggy.navigator.session.agent.pipeline;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.session.agent.TaskSubmittingA2aAgentDecorator;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultAgentSubmitPipelineTest {

    @Test
    void submit_runsSupportedStagesInOrder() {
        List<String> calls = new ArrayList<>();
        A2aTask expected = A2aTask.builder().id("task-1").contextId("ctx-1").build();
        AgentSubmitPipeline pipeline = new DefaultAgentSubmitPipeline(List.of(
                stage("late", 20, true, calls, null),
                stage("skip", 10, false, calls, null),
                stage("early", 0, true, calls, null),
                terminalStage(calls, expected)
        ));

        A2aTask actual = pipeline.submit(AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .contextId("ctx-1")
                .build()).getTask();

        assertSame(expected, actual);
        assertEquals(List.of("early:before", "late:before", "terminal", "late:after", "early:after"), calls);
    }

    @Test
    void decoratorAppliesDefaultsBeforeSubmittingToPipeline() {
        AgentResolveContext defaultContext = AgentResolveContext.builder()
                .tenantId("tenant-1")
                .requestSource("TEST")
                .build();
        List<AgentTaskSubmitRequest> submitted = new ArrayList<>();
        A2aTask expected = A2aTask.builder().id("task-2").build();
        AgentSubmitPipeline pipeline = request -> {
            submitted.add(request);
            return AgentTaskSubmitResult.of(expected);
        };
        TaskSubmittingA2aAgentDecorator decorator = new TaskSubmittingA2aAgentDecorator(
                new StubAgent(), pipeline, "agent-default", defaultContext);

        A2aTask actual = decorator.submitTask(new AgentTaskSubmitRequest());

        assertSame(expected, actual);
        assertEquals("agent-default", submitted.get(0).getAgentId());
        assertSame(defaultContext, submitted.get(0).getResolveContext());
    }

    @Test
    void validationRejectsMissingExecutionTarget() {
        AgentSubmitValidationPipelineStage stage = new AgentSubmitValidationPipelineStage();
        AgentTaskSubmitRequest request = AgentTaskSubmitRequest.builder()
                .prompt("hello")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> stage.handle(request, ignored -> AgentTaskSubmitResult.of(A2aTask.builder().build())));

        assertEquals("agentId, directoryId, workerId, or providerType is required", error.getMessage());
    }

    @Test
    void resourceProjectionNormalizesMessageFieldsOntoRequest() {
        AgentSubmitResourceProjectionPipelineStage stage = new AgentSubmitResourceProjectionPipelineStage();
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text("hello")));
        message.setContextId("ctx-1");
        message.setMetadata(Map.of("fromMessage", true));
        AgentTaskSubmitRequest request = AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .message(message)
                .metadata(Map.of("fromRequest", "yes"))
                .build();

        stage.handle(request, projected -> {
            assertEquals("hello", projected.getPrompt());
            assertEquals("ctx-1", projected.getContextId());
            assertEquals(true, projected.getMetadata().get("fromMessage"));
            assertEquals("yes", projected.getMetadata().get("fromRequest"));
            return AgentTaskSubmitResult.of(A2aTask.builder().id("task-1").build());
        });

        assertNull(request.getContextAlias());
    }

    private AgentSubmitPipelineStage stage(String name,
                                           int order,
                                           boolean supported,
                                           List<String> calls,
                                           A2aTask result) {
        return new AgentSubmitPipelineStage() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public boolean supports(AgentTaskSubmitRequest request) {
                return supported;
            }

            @Override
            public AgentTaskSubmitResult handle(AgentTaskSubmitRequest request, AgentSubmitPipelineChain chain) {
                calls.add(name + ":before");
                AgentTaskSubmitResult task = result != null ? AgentTaskSubmitResult.of(result) : chain.proceed(request);
                calls.add(name + ":after");
                return task;
            }
        };
    }

    private AgentSubmitPipelineStage terminalStage(List<String> calls, A2aTask expected) {
        return new AgentSubmitPipelineStage() {
            @Override
            public String name() {
                return "terminal";
            }

            @Override
            public int order() {
                return Integer.MAX_VALUE;
            }

            @Override
            public AgentTaskSubmitResult handle(AgentTaskSubmitRequest request, AgentSubmitPipelineChain chain) {
                calls.add("terminal");
                return AgentTaskSubmitResult.of(expected);
            }
        };
    }

    private static final class StubAgent implements A2aAgent {

        @Override
        public A2aAgentCard getAgentCard() {
            return A2aAgentCard.builder().name("stub").build();
        }

        @Override
        public A2aTask sendTask(A2aMessage message) {
            return A2aTask.builder().id("delegate-task").build();
        }

        @Override
        public Optional<A2aTask> getTask(String taskId) {
            return Optional.empty();
        }

        @Override
        public void cancelTask(String taskId) {
            // no-op
        }
    }
}
