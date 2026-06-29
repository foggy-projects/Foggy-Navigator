package com.foggy.navigator.session.agent.pipeline;

import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.session.repository.SessionCodingAgentRepository;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSubmitDirectoryResolutionPipelineStageTest {

    @Mock
    private SessionCodingAgentRepository codingAgentRepository;

    @Test
    void keepsExplicitDirectoryIdAndMirrorsCanonicalMetadata() {
        AgentSubmitDirectoryResolutionPipelineStage stage = stage();
        AgentTaskSubmitRequest request = AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .directoryId(" dir-explicit ")
                .metadata(Map.of("directory_id", "dir-other"))
                .build();

        stage.handle(request, projected -> {
            assertEquals("dir-explicit", projected.getDirectoryId());
            assertEquals("dir-explicit", projected.getMetadata().get("directoryId"));
            assertEquals("dir-other", projected.getMetadata().get("directory_id"));
            return AgentTaskSubmitResult.of(A2aTask.builder().id("task-1").build());
        });

        verifyNoInteractions(codingAgentRepository);
    }

    @Test
    void promotesDirectoryIdFromMetadataAlias() {
        AgentSubmitDirectoryResolutionPipelineStage stage = stage();
        AgentTaskSubmitRequest request = AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .metadata(Map.of("working_directory_id", " dir-meta "))
                .build();

        stage.handle(request, projected -> {
            assertEquals("dir-meta", projected.getDirectoryId());
            assertEquals("dir-meta", projected.getMetadata().get("directoryId"));
            return AgentTaskSubmitResult.of(A2aTask.builder().id("task-1").build());
        });

        verifyNoInteractions(codingAgentRepository);
    }

    @Test
    void resolvesDefaultDirectoryByTenantScopedAgent() {
        AgentSubmitDirectoryResolutionPipelineStage stage = stage();
        CodingAgentEntity agent = agent(" dir-default ");
        when(codingAgentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agent));
        AgentTaskSubmitRequest request = AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .resolveContext(AgentResolveContext.builder()
                        .tenantId("tenant-1")
                        .userId("user-1")
                        .build())
                .build();

        stage.handle(request, projected -> {
            assertEquals("dir-default", projected.getDirectoryId());
            assertEquals("dir-default", projected.getMetadata().get("directoryId"));
            return AgentTaskSubmitResult.of(A2aTask.builder().id("task-1").build());
        });

        verify(codingAgentRepository).findByAgentIdAndTenantId("agent-1", "tenant-1");
    }

    @Test
    void resolvesDefaultDirectoryByUserWhenTenantMissing() {
        AgentSubmitDirectoryResolutionPipelineStage stage = stage();
        when(codingAgentRepository.findByAgentIdAndUserId("agent-1", "user-1"))
                .thenReturn(Optional.of(agent("dir-user-default")));
        AgentTaskSubmitRequest request = AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .resolveContext(AgentResolveContext.builder()
                        .userId("user-1")
                        .build())
                .build();

        stage.handle(request, projected -> {
            assertEquals("dir-user-default", projected.getDirectoryId());
            return AgentTaskSubmitResult.of(A2aTask.builder().id("task-1").build());
        });

        verify(codingAgentRepository).findByAgentIdAndUserId("agent-1", "user-1");
    }

    @Test
    void leavesDirectoryIdMissingWithoutTenantOrUserScope() {
        AgentSubmitDirectoryResolutionPipelineStage stage = stage();
        AgentTaskSubmitRequest request = AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .build();

        stage.handle(request, projected -> {
            assertNull(projected.getDirectoryId());
            return AgentTaskSubmitResult.of(A2aTask.builder().id("task-1").build());
        });

        verifyNoInteractions(codingAgentRepository);
    }

    private AgentSubmitDirectoryResolutionPipelineStage stage() {
        return new AgentSubmitDirectoryResolutionPipelineStage(codingAgentRepository);
    }

    private CodingAgentEntity agent(String defaultDirectoryId) {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("agent-1");
        entity.setTenantId("tenant-1");
        entity.setUserId("user-1");
        entity.setDefaultDirectoryId(defaultDirectoryId);
        entity.setEnabled(true);
        return entity;
    }
}
