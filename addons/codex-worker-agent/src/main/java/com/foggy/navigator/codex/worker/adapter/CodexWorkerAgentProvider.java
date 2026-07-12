package com.foggy.navigator.codex.worker.adapter;

import com.foggy.navigator.codex.worker.repository.CodexCodingAgentRepository;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** A2A provider for the SDK/exec Codex Worker only. */
@Component
public class CodexWorkerAgentProvider extends AbstractCodexWorkerAgentProvider {

    public CodexWorkerAgentProvider(CodexCodingAgentRepository agentRepository,
                                    CodexTaskService taskService,
                                    LlmModelManager llmModelManager,
                                    @Nullable AgentContextStore contextStore,
                                    @Nullable WorkerManagementFacade workerManagementFacade) {
        super(agentRepository, taskService, llmModelManager, contextStore, workerManagementFacade);
    }

    @Override
    public String getProviderType() {
        return ProviderRouteRegistry.PROVIDER_CODEX_WORKER;
    }

    @Override
    protected boolean acceptsLegacyAgent(CodingAgentEntity entity) {
        return "LOCAL_CODEX_WORKER".equals(entity.getAgentType());
    }

    @Override
    protected String description() {
        return "Execute coding tasks via the OpenAI Codex SDK Worker";
    }
}
