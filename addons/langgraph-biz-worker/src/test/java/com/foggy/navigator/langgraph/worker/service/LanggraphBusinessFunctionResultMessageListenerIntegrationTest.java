package com.foggy.navigator.langgraph.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = LanggraphBusinessFunctionResultMessageListenerIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
class LanggraphBusinessFunctionResultMessageListenerIntegrationTest {

    @EnableAutoConfiguration(excludeName = {
            "com.foggy.navigator.business.agent.config.BusinessAgentAutoConfiguration",
            "com.foggy.navigator.langgraph.worker.config.LanggraphWorkerAutoConfiguration",
            "com.foggy.navigator.session.config.SessionModuleAutoConfiguration",
            "com.foggy.navigator.agent.framework.config.AgentFrameworkAutoConfiguration"
    })
    @EntityScan(basePackages = {
            "com.foggy.navigator.langgraph.worker.model.entity",
            "com.foggy.navigator.common.entity"
    })
    @EnableJpaRepositories(basePackages = {
            "com.foggy.navigator.langgraph.worker.repository",
            "com.foggy.navigator.common.repository"
    })
    @Import({
            LanggraphBusinessFunctionResultMessageListener.class,
            LanggraphTaskService.class
    })
    static class TestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        LanggraphWorkerService langgraphWorkerService() {
            return mock(LanggraphWorkerService.class);
        }

        @Bean
        SessionManager sessionManager() {
            return mock(SessionManager.class);
        }

        @Bean
        SessionMessageRepository sessionMessageRepository() {
            return mock(SessionMessageRepository.class);
        }
    }

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private LanggraphTaskRepository taskRepository;

    @Autowired
    private SessionTaskRepository sessionTaskRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        sessionTaskRepository.deleteAll();
        taskRepository.deleteAll();
    }

    @Test
    void failureResultPublishedAfterCommitPersistsVisibleTaskTerminalStatus() {
        String taskId = "lgt_after_commit_failed";
        persistRunningTask(taskId);

        publishAfterCommit(AgentMessage.of(
                "worker_session_1",
                "business-agent",
                MessageType.TEXT_COMPLETE,
                Map.of(
                        "subtype", "business_function_result_message",
                        "content", "Business function failed: adapter 401",
                        "status", "FAILED",
                        "executionStatus", "FAILED",
                        "workerTaskId", taskId,
                        "businessTaskId", "obt_1"
                )));

        LanggraphTaskEntity task = taskRepository.findByTaskId(taskId).orElseThrow();
        assertEquals("FAILED", task.getStatus());
        assertEquals("Business function failed: adapter 401", task.getErrorMessage());
        assertNotNull(task.getUpdatedAt());

        SessionTaskEntity projection = sessionTaskRepository.findByTaskId(taskId).orElseThrow();
        assertEquals("FAILED", projection.getStatus());
        assertEquals("Business function failed: adapter 401", projection.getErrorMessage());
    }

    @Test
    void successResultPublishedAfterCommitPersistsVisibleTaskTerminalStatus() {
        String taskId = "lgt_after_commit_success";
        persistRunningTask(taskId);

        publishAfterCommit(AgentMessage.of(
                "worker_session_1",
                "business-agent",
                MessageType.TEXT_COMPLETE,
                Map.of(
                        "subtype", "business_function_result_message",
                        "content", "Vehicle created.",
                        "status", "SUCCESS",
                        "executionStatus", "COMPLETED",
                        "workerTaskId", taskId,
                        "businessTaskId", "obt_1"
                )));

        LanggraphTaskEntity task = taskRepository.findByTaskId(taskId).orElseThrow();
        assertEquals("COMPLETED", task.getStatus());
        assertEquals("Vehicle created.", task.getResultText());
        assertNotNull(task.getStructuredOutput());

        SessionTaskEntity projection = sessionTaskRepository.findByTaskId(taskId).orElseThrow();
        assertEquals("COMPLETED", projection.getStatus());
        assertEquals("Vehicle created.", projection.getResultText());
    }

    private void publishAfterCommit(AgentMessage message) {
        transactionTemplate.executeWithoutResult(status ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        eventPublisher.publishEvent(message);
                    }
                }));
    }

    private void persistRunningTask(String taskId) {
        LanggraphTaskEntity task = new LanggraphTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId("worker_session_1");
        task.setWorkerId("worker_1");
        task.setAgentId("tms-root-router-agent");
        task.setUserId("user_1");
        task.setTenantId("tenant_1");
        task.setPrompt("create vehicle");
        task.setStatus("RUNNING");
        taskRepository.saveAndFlush(task);
    }
}
