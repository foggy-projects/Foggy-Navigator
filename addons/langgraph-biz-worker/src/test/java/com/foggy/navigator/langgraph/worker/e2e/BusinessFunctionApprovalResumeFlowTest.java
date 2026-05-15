package com.foggy.navigator.langgraph.worker.e2e;

import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.business.agent.event.BusinessSuspensionResumeDecisionEvent;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionSuspensionEntity;
import com.foggy.navigator.business.agent.model.form.WorkerGatewayResumeForm;
import com.foggy.navigator.business.agent.repository.BusinessFunctionSuspensionRepository;
import com.foggy.navigator.business.agent.service.BusinessFunctionAuthorizationService;
import com.foggy.navigator.business.agent.service.BusinessFunctionRuntimeAuditService;
import com.foggy.navigator.business.agent.service.BusinessFunctionSuspensionService;
import com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterInvoker;
import com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterResult;
import com.foggy.navigator.common.event.WorkerGatewayResumeEvent;
import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.langgraph.worker.service.LanggraphWorkerResumeEventListener;
import com.foggy.navigator.langgraph.worker.service.LanggraphWorkerService;
import com.foggy.navigator.session.event.SessionEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessFunctionApprovalResumeFlowTest {

    private static final String TENANT_ID = "tenant_approval_flow";
    private static final String CLIENT_APP_ID = "client_app_approval_flow";
    private static final String UPSTREAM_USER_ID = "upstream_user_approval_flow";
    private static final String SKILL_ID = "order_skill";
    private static final String FUNCTION_ID = "order.close.submit";
    private static final String VERSION = "v1";
    private static final String BUSINESS_TASK_ID = "bat_1";
    private static final String BUSINESS_SESSION_ID = "business_session_1";
    private static final String WORKER_TASK_ID = "lgt_1";
    private static final String WORKER_SESSION_ID = "worker_session_1";
    private static final String SUSPEND_ID = "sus_approval_flow";
    private static final String INPUT_JSON = "{\"orderId\":\"ORD-1\"}";

    @Mock
    private BusinessFunctionSuspensionRepository suspensionRepository;
    @Mock
    private BusinessFunctionRuntimeAuditService auditService;
    @Mock
    private BusinessFunctionAuthorizationService authorizationService;
    @Mock
    private BusinessFunctionAdapterInvoker adapterInvoker;
    @Mock
    private LanggraphTaskRepository taskRepository;
    @Mock
    private LanggraphWorkerService workerService;
    @Mock
    private LanggraphWorkerClient workerClient;
    @Mock
    private SessionEventListener sessionEventListener;

    private BusinessFunctionSuspensionService suspensionService;
    private LanggraphWorkerResumeEventListener resumeEventListener;
    private DispatchingEventPublisher eventPublisher;
    private BusinessFunctionSuspensionEntity suspension;

    @BeforeEach
    void setUp() {
        eventPublisher = new DispatchingEventPublisher();
        suspensionService = new BusinessFunctionSuspensionService(
                suspensionRepository,
                eventPublisher,
                auditService,
                authorizationService,
                adapterInvoker
        );
        resumeEventListener = new LanggraphWorkerResumeEventListener(
                taskRepository,
                workerService,
                sessionEventListener
        );
        eventPublisher.setSuspensionService(suspensionService);
        eventPublisher.setResumeEventListener(resumeEventListener);
        eventPublisher.setSessionEventListener(sessionEventListener);

        suspension = buildPendingSuspension();
        when(suspensionRepository.findBySuspendId(SUSPEND_ID)).thenReturn(Optional.of(suspension));
        when(suspensionRepository.save(any(BusinessFunctionSuspensionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        LanggraphTaskEntity task = new LanggraphTaskEntity();
        task.setTaskId(WORKER_TASK_ID);
        task.setSessionId(WORKER_SESSION_ID);
        task.setWorkerId("worker_1");
        task.setTenantId(TENANT_ID);
        when(taskRepository.findByTaskId(WORKER_TASK_ID)).thenReturn(Optional.of(task));
        when(workerService.getWorkerEntity("worker_1")).thenReturn(new LanggraphWorkerEntity());
        when(workerService.createClient(any(LanggraphWorkerEntity.class))).thenReturn(workerClient);
        when(workerClient.resumeTask(WORKER_TASK_ID, "approved", "同意提交"))
                .thenReturn(Mono.just(Map.of(
                        "resume_message", Map.of(
                                "content", "审批已通过，已继续提交关单申请。",
                                "source", "function_input.approved"
                        )
                )));

        when(authorizationService.resolveExecutableBusinessFunction(
                TENANT_ID,
                CLIENT_APP_ID,
                UPSTREAM_USER_ID,
                SKILL_ID,
                FUNCTION_ID,
                VERSION
        )).thenReturn(new BusinessFunctionRuntimeContextDTO());
        when(adapterInvoker.invoke(any(BusinessFunctionRuntimeContextDTO.class), eq(INPUT_JSON)))
                .thenReturn(successResult("关单申请已提交。"));
    }

    @Test
    void approvedSuspensionDispatchesWorkerResumeThenBusinessExecutionResult() {
        WorkerGatewayResumeForm form = buildApprovedResumeForm();

        suspensionService.resumeSuspension(TENANT_ID, "admin_1", SUSPEND_ID, form);

        assertEquals("COMPLETED", suspension.getStatus());
        assertEquals("COMPLETED", suspension.getBusinessExecutionStatus());
        verify(workerClient).resumeTask(WORKER_TASK_ID, "approved", "同意提交");
        verify(adapterInvoker).invoke(any(BusinessFunctionRuntimeContextDTO.class), eq(INPUT_JSON));

        ArgumentCaptor<AgentMessage> messageCaptor = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener, atLeastOnce()).handleMessage(messageCaptor.capture());
        List<AgentMessage> messages = messageCaptor.getAllValues();
        assertEquals(2, messages.size());

        AgentMessage postApproval = messages.get(0);
        assertEquals(WORKER_SESSION_ID, postApproval.getSessionId());
        assertEquals(WORKER_TASK_ID, postApproval.getTaskId());
        assertEquals(MessageType.TEXT_COMPLETE, postApproval.getType());
        Map<?, ?> postApprovalPayload = assertInstanceOf(Map.class, postApproval.getPayload());
        assertEquals("post_approval_message", postApprovalPayload.get("subtype"));
        assertEquals("审批已通过，已继续提交关单申请。", postApprovalPayload.get("content"));
        assertEquals("approved", postApprovalPayload.get("approvalResult"));
        assertEquals(SUSPEND_ID, postApprovalPayload.get("suspendId"));

        AgentMessage businessResult = messages.get(1);
        assertEquals(WORKER_SESSION_ID, businessResult.getSessionId());
        assertEquals(WORKER_TASK_ID, businessResult.getTaskId());
        assertEquals(MessageType.TEXT_COMPLETE, businessResult.getType());
        Map<?, ?> businessPayload = assertInstanceOf(Map.class, businessResult.getPayload());
        assertEquals("business_function_result_message", businessPayload.get("subtype"));
        assertEquals("关单申请已提交。", businessPayload.get("content"));
        assertEquals("SUCCESS", businessPayload.get("status"));
        assertEquals("COMPLETED", businessPayload.get("executionStatus"));
        assertEquals(SUSPEND_ID, businessPayload.get("suspendId"));
        assertEquals(FUNCTION_ID, businessPayload.get("functionId"));
        assertEquals(VERSION, businessPayload.get("version"));
        assertEquals(BUSINESS_TASK_ID, businessPayload.get("businessTaskId"));
        assertEquals(BUSINESS_SESSION_ID, businessPayload.get("businessSessionId"));

        assertNotNull(eventPublisher.findEvent(WorkerGatewayResumeEvent.class));
        assertNotNull(eventPublisher.findEvent(BusinessSuspensionResumeDecisionEvent.class));
    }

    private BusinessFunctionSuspensionEntity buildPendingSuspension() {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId(SUSPEND_ID);
        entity.setTaskId(BUSINESS_TASK_ID);
        entity.setWorkerTaskId(WORKER_TASK_ID);
        entity.setSessionId(BUSINESS_SESSION_ID);
        entity.setWorkerSessionId(WORKER_SESSION_ID);
        entity.setTenantId(TENANT_ID);
        entity.setClientAppId(CLIENT_APP_ID);
        entity.setUpstreamUserId(UPSTREAM_USER_ID);
        entity.setSkillId(SKILL_ID);
        entity.setFunctionId(FUNCTION_ID);
        entity.setVersion(VERSION);
        entity.setInputJson(INPUT_JSON);
        entity.setStatus("PENDING");
        entity.setExpiresAt(LocalDateTime.now().plusHours(1));
        return entity;
    }

    private WorkerGatewayResumeForm buildApprovedResumeForm() {
        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();

        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId(CLIENT_APP_ID);
        binding.setUpstreamUserId(UPSTREAM_USER_ID);
        binding.setTaskId(BUSINESS_TASK_ID);
        binding.setSessionId(BUSINESS_SESSION_ID);
        binding.setFunctionId(FUNCTION_ID);
        binding.setVersion(VERSION);
        binding.setInputHash(BusinessFunctionRuntimeAuditService.sha256(INPUT_JSON));
        form.setBindingContext(binding);

        WorkerGatewayResumeForm.ApprovalResult approval = new WorkerGatewayResumeForm.ApprovalResult();
        approval.setStatus("approved");
        approval.setComment("同意提交");
        form.setApprovalResult(approval);
        return form;
    }

    private BusinessFunctionAdapterResult successResult(String message) {
        BusinessFunctionAdapterResult result = BusinessFunctionAdapterResult.success("{\"code\":200,\"data\":{\"accepted\":true}}");
        result.setMessage(message);
        return result;
    }

    private static class DispatchingEventPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();
        private BusinessFunctionSuspensionService suspensionService;
        private LanggraphWorkerResumeEventListener resumeEventListener;
        private SessionEventListener sessionEventListener;

        void setSuspensionService(BusinessFunctionSuspensionService suspensionService) {
            this.suspensionService = suspensionService;
        }

        void setResumeEventListener(LanggraphWorkerResumeEventListener resumeEventListener) {
            this.resumeEventListener = resumeEventListener;
        }

        void setSessionEventListener(SessionEventListener sessionEventListener) {
            this.sessionEventListener = sessionEventListener;
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            publishEvent((Object) event);
        }

        @Override
        public void publishEvent(Object event) {
            events.add(event);
            if (event instanceof WorkerGatewayResumeEvent resumeEvent) {
                resumeEventListener.handleWorkerGatewayResumeEvent(resumeEvent);
            } else if (event instanceof BusinessSuspensionResumeDecisionEvent decisionEvent) {
                suspensionService.handleBusinessSuspensionResumeDecisionEvent(decisionEvent);
            } else if (event instanceof AgentMessage message) {
                sessionEventListener.handleMessage(message);
            }
        }

        <T> T findEvent(Class<T> type) {
            return events.stream()
                    .filter(type::isInstance)
                    .map(type::cast)
                    .findFirst()
                    .orElse(null);
        }
    }
}
