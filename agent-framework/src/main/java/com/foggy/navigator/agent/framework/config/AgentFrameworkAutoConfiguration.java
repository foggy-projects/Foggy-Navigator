package com.foggy.navigator.agent.framework.config;

import com.foggy.navigator.agent.framework.core.AgentInvoker;
import com.foggy.navigator.agent.framework.core.AgentRegistry;
import com.foggy.navigator.agent.framework.core.impl.DefaultAgentInvoker;
import com.foggy.navigator.agent.framework.llm.LlmAdapter;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.agent.framework.session.impl.InMemorySessionManager;
import com.foggy.navigator.agent.framework.skill.SkillManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Agent Framework 模块自动配置
 * 提供智能体核心功能，包括会话管理、技能管理、工具管理等
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.agent.framework.*"
})
public class AgentFrameworkAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SessionManager.class)
    public SessionManager inMemorySessionManager() {
        return new InMemorySessionManager();
    }

    @Bean
    @ConditionalOnMissingBean(AgentInvoker.class)
    public AgentInvoker defaultAgentInvoker(AgentRegistry registry,
                                            SessionManager sessionManager,
                                            LlmAdapter llmAdapter,
                                            ApplicationEventPublisher publisher,
                                            AsyncTaskExecutor agentExecutor,
                                            SkillManager skillManager) {
        return new DefaultAgentInvoker(registry, sessionManager, llmAdapter, publisher, agentExecutor, skillManager);
    }

    @Bean("agentExecutor")
    @ConditionalOnMissingBean(name = "agentExecutor")
    public AsyncTaskExecutor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-invoke-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
