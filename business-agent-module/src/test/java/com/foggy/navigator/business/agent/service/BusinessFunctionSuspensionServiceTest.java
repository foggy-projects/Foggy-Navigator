package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.common.event.WorkerGatewayResumeEvent;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionVersionDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionSuspensionEntity;
import com.foggy.navigator.business.agent.model.form.WorkerGatewayResumeForm;
import com.foggy.navigator.business.agent.repository.BusinessFunctionSuspensionRepository;
import com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterInvoker;
import com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessFunctionSuspensionServiceTest {

    @Mock
    private BusinessFunctionSuspensionRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private BusinessFunctionRuntimeAuditService auditService;

    @Mock
    private BusinessFunctionAuthorizationService authorizationService;

    @Mock
    private BusinessFunctionAdapterInvoker adapterInvoker;

    @InjectMocks
    private BusinessFunctionSuspensionService suspensionService;

    @Captor
    private ArgumentCaptor<BusinessFunctionSuspensionEntity> entityCaptor;

    @Captor
    private ArgumentCaptor<WorkerGatewayResumeEvent> eventCaptor;

    private BusinessTaskScopedTokenDTO tokenDTO;

    @BeforeEach
    void setUp() {
        tokenDTO = new BusinessTaskScopedTokenDTO();
        tokenDTO.setTaskId("task1");
        tokenDTO.setSessionId("session1");
        tokenDTO.setTenantId("tenant1");
        tokenDTO.setClientAppId("app1");
        tokenDTO.setUpstreamUserId("user1");
        tokenDTO.setSkillId("skill1");
        tokenDTO.setWorkerPoolId("pool1");
        tokenDTO.setWorkerSessionId("worker_session1");
    }

    @Test
    void createSuspension_success() {
        when(repository.save(any(BusinessFunctionSuspensionEntity.class))).thenAnswer(i -> i.getArguments()[0]);
        tokenDTO.setWorkerTaskId("lgt_1");

        BusinessFunctionSuspensionEntity result = suspensionService.createSuspension(tokenDTO, "f1", "v1", "{}", "idem_key");

        assertNotNull(result);
        assertTrue(result.getSuspendId().startsWith("sus_"));
        assertEquals("task1", result.getTaskId());
        assertEquals("lgt_1", result.getWorkerTaskId());
        assertEquals("worker_session1", result.getWorkerSessionId());
        assertEquals("pool1", result.getWorkerPoolId());
        assertEquals("f1", result.getFunctionId());
        assertEquals("PENDING", result.getStatus());
        assertNotNull(result.getExpiresAt());

        verify(repository).save(any(BusinessFunctionSuspensionEntity.class));
    }

    @Test
    void resumeSuspension_approved() {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_1");
        entity.setTaskId("task1");
        entity.setSessionId("session1");
        entity.setTenantId("tenant1");
        entity.setClientAppId("app1");
        entity.setUpstreamUserId("user1");
        entity.setFunctionId("f1");
        entity.setVersion("v1");
        entity.setInputJson("{\"key\":\"value\"}");
        entity.setStatus("PENDING");
        entity.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(repository.findBySuspendId("sus_1")).thenReturn(Optional.of(entity));

        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();
        WorkerGatewayResumeForm.ApprovalResult result = new WorkerGatewayResumeForm.ApprovalResult();
        result.setStatus("approved");
        result.setComment("LGTM");
        form.setApprovalResult(result);

        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId("app1");
        binding.setUpstreamUserId("user1");
        binding.setTaskId("task1");
        binding.setSessionId("session1");
        binding.setFunctionId("f1");
        binding.setVersion("v1");
        binding.setInputHash(computeSha256Hex("{\"key\":\"value\"}"));
        form.setBindingContext(binding);

        suspensionService.resumeSuspension("tenant1", "admin1", "sus_1", form);

        verify(repository).save(entityCaptor.capture());
        BusinessFunctionSuspensionEntity saved = entityCaptor.getValue();
        assertEquals("RESUME_DISPATCHED", saved.getStatus());
        assertEquals("LGTM", saved.getComment());
        assertEquals("admin1", saved.getApprovedBy());

        verify(eventPublisher).publishEvent(eventCaptor.capture());
        WorkerGatewayResumeEvent event = eventCaptor.getValue();
        assertEquals("task1", event.getTaskId());
        assertEquals("sus_1", event.getSuspendId());
        assertEquals("approved", event.getApprovalResult());
        assertEquals("LGTM", event.getComment());
    }

    @Test
    void resumeSuspension_dispatchesWorkerTaskId_whenBound() {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_1");
        entity.setTaskId("task1");
        entity.setWorkerTaskId("lgt_1");
        entity.setWorkerSessionId("worker_session1");
        entity.setSessionId("session1");
        entity.setTenantId("tenant1");
        entity.setClientAppId("app1");
        entity.setUpstreamUserId("user1");
        entity.setFunctionId("f1");
        entity.setVersion("v1");
        entity.setInputJson("{\"key\":\"value\"}");
        entity.setStatus("PENDING");
        entity.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(repository.findBySuspendId("sus_1")).thenReturn(Optional.of(entity));

        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();
        WorkerGatewayResumeForm.ApprovalResult result = new WorkerGatewayResumeForm.ApprovalResult();
        result.setStatus("approved");
        form.setApprovalResult(result);

        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId("app1");
        binding.setUpstreamUserId("user1");
        binding.setTaskId("task1");
        binding.setSessionId("session1");
        binding.setFunctionId("f1");
        binding.setVersion("v1");
        binding.setInputHash(computeSha256Hex("{\"key\":\"value\"}"));
        form.setBindingContext(binding);

        suspensionService.resumeSuspension("tenant1", "admin1", "sus_1", form);

        verify(eventPublisher).publishEvent(eventCaptor.capture());
        WorkerGatewayResumeEvent event = eventCaptor.getValue();
        assertEquals("lgt_1", event.getTaskId());
        assertEquals("worker_session1", event.getSessionId());
        assertEquals("session1", event.getBusinessSessionId());
    }

    @Test
    void resumeSuspension_rejected() {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_1");
        entity.setTaskId("task1");
        entity.setSessionId("session1");
        entity.setTenantId("tenant1");
        entity.setClientAppId("app1");
        entity.setUpstreamUserId("user1");
        entity.setFunctionId("f1");
        entity.setVersion("v1");
        entity.setInputJson("{\"key\":\"value\"}");
        entity.setStatus("PENDING");
        entity.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(repository.findBySuspendId("sus_1")).thenReturn(Optional.of(entity));

        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();
        WorkerGatewayResumeForm.ApprovalResult result = new WorkerGatewayResumeForm.ApprovalResult();
        result.setStatus("rejected");
        form.setApprovalResult(result);

        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId("app1");
        binding.setUpstreamUserId("user1");
        binding.setTaskId("task1");
        binding.setSessionId("session1");
        binding.setFunctionId("f1");
        binding.setVersion("v1");
        binding.setInputHash(computeSha256Hex("{\"key\":\"value\"}"));
        form.setBindingContext(binding);

        suspensionService.resumeSuspension("tenant1", "admin1", "sus_1", form);

        verify(repository).save(entityCaptor.capture());
        assertEquals("REJECTED", entityCaptor.getValue().getStatus());

        verify(eventPublisher).publishEvent(any(WorkerGatewayResumeEvent.class));
    }

    @Test
    void resumeSuspension_fails_if_already_processed() {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_1");
        entity.setStatus("APPROVED");

        when(repository.findBySuspendId("sus_1")).thenReturn(Optional.of(entity));

        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            suspensionService.resumeSuspension("tenant1", "admin1", "sus_1", form)
        );
        assertTrue(ex.getMessage().contains("not in PENDING state"));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void resumeSuspension_fails_if_expired() {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_1");
        entity.setStatus("PENDING");
        entity.setExpiresAt(LocalDateTime.now().minusMinutes(5));

        when(repository.findBySuspendId("sus_1")).thenReturn(Optional.of(entity));

        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            suspensionService.resumeSuspension("tenant1", "admin1", "sus_1", form)
        );
        assertTrue(ex.getMessage().contains("expired"));

        verify(repository).save(entityCaptor.capture());
        assertEquals("EXPIRED", entityCaptor.getValue().getStatus());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void resumeSuspension_fails_on_binding_mismatch() {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_1");
        entity.setTaskId("task1");
        entity.setSessionId("session1");
        entity.setTenantId("tenant1");
        entity.setClientAppId("app1");
        entity.setUpstreamUserId("user1");
        entity.setFunctionId("f1");
        entity.setStatus("PENDING");
        entity.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(repository.findBySuspendId("sus_1")).thenReturn(Optional.of(entity));

        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();
        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId("app2"); // Mismatch
        binding.setUpstreamUserId("user1");
        binding.setTaskId("task1");
        binding.setSessionId("session1");
        binding.setFunctionId("f1");
        form.setBindingContext(binding);

        SecurityException ex = assertThrows(SecurityException.class, () ->
            suspensionService.resumeSuspension("tenant1", "admin1", "sus_1", form)
        );
        assertTrue(ex.getMessage().contains("Client App ID mismatch"));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void resumeSuspension_fails_on_version_mismatch() {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_1");
        entity.setTaskId("task1");
        entity.setSessionId("session1");
        entity.setTenantId("tenant1");
        entity.setClientAppId("app1");
        entity.setUpstreamUserId("user1");
        entity.setFunctionId("f1");
        entity.setVersion("v1");
        entity.setStatus("PENDING");
        entity.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(repository.findBySuspendId("sus_1")).thenReturn(Optional.of(entity));

        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();
        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId("app1");
        binding.setUpstreamUserId("user1");
        binding.setTaskId("task1");
        binding.setSessionId("session1");
        binding.setFunctionId("f1");
        binding.setVersion("v2"); // Mismatch
        form.setBindingContext(binding);

        SecurityException ex = assertThrows(SecurityException.class, () ->
            suspensionService.resumeSuspension("tenant1", "admin1", "sus_1", form)
        );
        assertTrue(ex.getMessage().contains("Version mismatch"));
    }

    @Test
    void resumeSuspension_fails_if_inputHash_missing() {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_1");
        entity.setTaskId("task1");
        entity.setSessionId("session1");
        entity.setTenantId("tenant1");
        entity.setClientAppId("app1");
        entity.setUpstreamUserId("user1");
        entity.setFunctionId("f1");
        entity.setVersion("v1");
        entity.setStatus("PENDING");
        entity.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(repository.findBySuspendId("sus_1")).thenReturn(Optional.of(entity));

        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();
        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId("app1");
        binding.setUpstreamUserId("user1");
        binding.setTaskId("task1");
        binding.setSessionId("session1");
        binding.setFunctionId("f1");
        binding.setVersion("v1");
        // Missing inputHash
        form.setBindingContext(binding);

        SecurityException ex = assertThrows(SecurityException.class, () ->
            suspensionService.resumeSuspension("tenant1", "admin1", "sus_1", form)
        );
        assertTrue(ex.getMessage().contains("input_hash is required"));
    }

    @Test
    void resumeSuspension_fails_if_inputHash_mismatch() {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_1");
        entity.setTaskId("task1");
        entity.setSessionId("session1");
        entity.setTenantId("tenant1");
        entity.setClientAppId("app1");
        entity.setUpstreamUserId("user1");
        entity.setFunctionId("f1");
        entity.setVersion("v1");
        entity.setInputJson("{\"key\":\"value\"}");
        entity.setStatus("PENDING");
        entity.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(repository.findBySuspendId("sus_1")).thenReturn(Optional.of(entity));

        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();
        WorkerGatewayResumeForm.BindingContext binding = new WorkerGatewayResumeForm.BindingContext();
        binding.setClientAppId("app1");
        binding.setUpstreamUserId("user1");
        binding.setTaskId("task1");
        binding.setSessionId("session1");
        binding.setFunctionId("f1");
        binding.setVersion("v1");
        binding.setInputHash(computeSha256Hex("{\"key\":\"wrong_value\"}"));
        form.setBindingContext(binding);

        SecurityException ex = assertThrows(SecurityException.class, () ->
            suspensionService.resumeSuspension("tenant1", "admin1", "sus_1", form)
        );
        assertTrue(ex.getMessage().contains("Input hash mismatch"));
    }

    @Test
    void resumeSuspension_nullForm_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            suspensionService.resumeSuspension("tenant1", "admin1", "sus_1", null)
        );
        assertTrue(ex.getMessage().contains("Resume form cannot be null"));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void executeApprovedSuspension_success_executesAdapterWithOriginalBinding() {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_1");
        entity.setTaskId("task1");
        entity.setWorkerTaskId("lgt_1");
        entity.setWorkerSessionId("worker_session1");
        entity.setSessionId("session1");
        entity.setTenantId("tenant1");
        entity.setClientAppId("app1");
        entity.setUpstreamUserId("user1");
        entity.setSkillId("skill1");
        entity.setWorkerPoolId("pool1");
        entity.setFunctionId("f1");
        entity.setVersion("v1");
        entity.setInputJson("{\"key\":\"value\"}");
        entity.setStatus("RESUME_DISPATCHED");

        when(repository.findBySuspendId("sus_1")).thenReturn(Optional.of(entity));
        when(repository.save(any(BusinessFunctionSuspensionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        BusinessFunctionRuntimeContextDTO context = new BusinessFunctionRuntimeContextDTO();
        BusinessFunctionDTO function = new BusinessFunctionDTO();
        function.setFunctionId("f1");
        function.setApprovalRequired(true);
        context.setFunction(function);
        BusinessFunctionVersionDTO version = new BusinessFunctionVersionDTO();
        version.setVersion("v1");
        context.setVersionData(version);

        when(authorizationService.resolveExecutableBusinessFunction("tenant1", "app1", "user1", "skill1", "f1", "v1"))
                .thenReturn(context);
        when(adapterInvoker.invoke(any(BusinessFunctionRuntimeContextDTO.class), eq("{\"key\":\"value\"}")))
                .thenReturn(BusinessFunctionAdapterResult.success("{\"code\":200,\"data\":{}}"));

        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("lgt_1")
                .sessionId("worker_session1")
                .businessSessionId("session1")
                .suspendId("sus_1")
                .approvalResult("approved")
                .tenantId("tenant1")
                .clientAppId("app1")
                .upstreamUserId("user1")
                .functionId("f1")
                .inputHash(computeSha256Hex("{\"key\":\"value\"}"))
                .build();

        WorkerGatewayInvokeResponseDTO response = suspensionService.executeApprovedSuspension(event);

        assertEquals(WorkerGatewayInvokeResponseDTO.STATUS_SUCCESS, response.getStatus());
        assertEquals("{\"code\":200,\"data\":{}}", response.getOutputJson());
        assertEquals("COMPLETED", entity.getStatus());

        ArgumentCaptor<BusinessFunctionRuntimeContextDTO> contextCaptor = ArgumentCaptor.forClass(BusinessFunctionRuntimeContextDTO.class);
        verify(adapterInvoker).invoke(contextCaptor.capture(), eq("{\"key\":\"value\"}"));
        assertEquals("task1", contextCaptor.getValue().getTaskId());
        assertEquals("session1", contextCaptor.getValue().getSessionId());
        assertEquals("app1", contextCaptor.getValue().getClientAppId());
        assertEquals("user1", contextCaptor.getValue().getUpstreamUserId());
        verify(auditService).recordInvokeSuccess(any(BusinessTaskScopedTokenDTO.class), eq("f1"), eq("v1"), any(), anyLong());
    }

    @Test
    void executeApprovedSuspension_rejectsInputHashMismatch() {
        BusinessFunctionSuspensionEntity entity = new BusinessFunctionSuspensionEntity();
        entity.setSuspendId("sus_1");
        entity.setTaskId("task1");
        entity.setWorkerTaskId("lgt_1");
        entity.setWorkerSessionId("worker_session1");
        entity.setSessionId("session1");
        entity.setTenantId("tenant1");
        entity.setClientAppId("app1");
        entity.setUpstreamUserId("user1");
        entity.setSkillId("skill1");
        entity.setFunctionId("f1");
        entity.setVersion("v1");
        entity.setInputJson("{\"key\":\"value\"}");
        entity.setStatus("RESUME_DISPATCHED");

        when(repository.findBySuspendId("sus_1")).thenReturn(Optional.of(entity));

        WorkerGatewayResumeEvent event = WorkerGatewayResumeEvent.builder()
                .source(this)
                .taskId("lgt_1")
                .sessionId("worker_session1")
                .businessSessionId("session1")
                .suspendId("sus_1")
                .approvalResult("approved")
                .tenantId("tenant1")
                .clientAppId("app1")
                .upstreamUserId("user1")
                .functionId("f1")
                .inputHash(computeSha256Hex("{\"key\":\"wrong\"}"))
                .build();

        SecurityException ex = assertThrows(SecurityException.class,
                () -> suspensionService.executeApprovedSuspension(event));
        assertTrue(ex.getMessage().contains("Input hash mismatch"));
        verifyNoInteractions(adapterInvoker);
    }

    private String computeSha256Hex(String input) {
        if (input == null) return "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
