package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionRuntimeAuditEntity;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRuntimeAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessFunctionRuntimeAuditServiceTest {

    @Mock
    private BusinessFunctionRuntimeAuditRepository repository;

    private BusinessFunctionRuntimeAuditService auditService;

    private BusinessTaskScopedTokenDTO token;

    @BeforeEach
    void setUp() {
        auditService = new BusinessFunctionRuntimeAuditService(repository);

        token = new BusinessTaskScopedTokenDTO();
        token.setTenantId("tenant1");
        token.setClientAppId("app1");
        token.setUpstreamUserId("user1");
        token.setTaskId("task1");
        token.setSessionId("session1");
        token.setWorkerPoolId("pool1");
        token.setSkillId("skill1");
    }

    @Test
    void recordInvokeStarted_persists_entity_with_correct_fields() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.recordInvokeStarted(token, "fn1", "v1", "hash123");

        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> captor = ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(repository).save(captor.capture());
        BusinessFunctionRuntimeAuditEntity entity = captor.getValue();

        assertNotNull(entity.getAuditId());
        assertTrue(entity.getAuditId().startsWith("aud_"));
        assertEquals("tenant1", entity.getTenantId());
        assertEquals("app1", entity.getClientAppId());
        assertEquals("user1", entity.getUpstreamUserId());
        assertEquals("task1", entity.getTaskId());
        assertEquals("session1", entity.getSessionId());
        assertEquals("pool1", entity.getWorkerPoolId());
        assertEquals("skill1", entity.getSkillId());
        assertEquals("fn1", entity.getFunctionId());
        assertEquals("v1", entity.getFunctionVersion());
        assertEquals("INVOKE_STARTED", entity.getEventType());
        assertEquals("STARTED", entity.getStatus());
        assertEquals("hash123", entity.getInputHash());
    }

    @Test
    void recordInvokeSuccess_persists_output_hash_and_duration() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.recordInvokeSuccess(token, "fn1", "v1", "outhash", 42L);

        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> captor = ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(repository).save(captor.capture());
        BusinessFunctionRuntimeAuditEntity entity = captor.getValue();

        assertEquals("INVOKE_SUCCESS", entity.getEventType());
        assertEquals("SUCCESS", entity.getStatus());
        assertEquals("outhash", entity.getOutputHash());
        assertEquals(42L, entity.getDurationMs());
    }

    @Test
    void recordInvokeSuccess_withSuspendId_persists_suspend_id() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.recordInvokeSuccess(token, "fn1", "v1", "sus_123", "outhash", 42L);

        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> captor = ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(repository).save(captor.capture());
        BusinessFunctionRuntimeAuditEntity entity = captor.getValue();

        assertEquals("INVOKE_SUCCESS", entity.getEventType());
        assertEquals("sus_123", entity.getSuspendId());
        assertEquals("outhash", entity.getOutputHash());
    }

    @Test
    void recordInvokeSuspended_persists_suspend_id() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.recordInvokeSuspended(token, "fn1", "v1", "sus_123");

        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> captor = ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(repository).save(captor.capture());
        BusinessFunctionRuntimeAuditEntity entity = captor.getValue();

        assertEquals("INVOKE_SUSPENDED", entity.getEventType());
        assertEquals("sus_123", entity.getSuspendId());
    }

    @Test
    void recordInvokeFailed_persists_error_details() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.recordInvokeFailed(token, "fn1", "v1", "ADAPTER_ERROR", "Connection refused", 100L);

        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> captor = ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(repository).save(captor.capture());
        BusinessFunctionRuntimeAuditEntity entity = captor.getValue();

        assertEquals("INVOKE_FAILED", entity.getEventType());
        assertEquals("FAILED", entity.getStatus());
        assertEquals("ADAPTER_ERROR", entity.getErrorCode());
        assertEquals("Connection refused", entity.getErrorMessage());
        assertEquals(100L, entity.getDurationMs());
    }

    @Test
    void recordInvokeFailed_withSuspendId_persists_suspend_id() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.recordInvokeFailed(token, "fn1", "v1", "sus_123", "ADAPTER_ERROR", "Connection refused", 100L);

        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> captor = ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(repository).save(captor.capture());
        BusinessFunctionRuntimeAuditEntity entity = captor.getValue();

        assertEquals("INVOKE_FAILED", entity.getEventType());
        assertEquals("sus_123", entity.getSuspendId());
        assertEquals("ADAPTER_ERROR", entity.getErrorCode());
    }

    @Test
    void recordToolMessage_persists_tool_info() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.recordToolMessage(token, "invoke_business_function", "fn1", "SUCCESS", "completed");

        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> captor = ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(repository).save(captor.capture());
        BusinessFunctionRuntimeAuditEntity entity = captor.getValue();

        assertEquals("TOOL_MESSAGE", entity.getEventType());
        assertEquals("SUCCESS", entity.getStatus());
        assertEquals("fn1", entity.getFunctionId());
        assertTrue(entity.getErrorMessage().contains("invoke_business_function"));
    }

    @Test
    void recordResumeRequested_persists_suspend_and_user() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.recordResumeRequested("tenant1", "sus_1", "admin");

        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> captor = ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(repository).save(captor.capture());
        BusinessFunctionRuntimeAuditEntity entity = captor.getValue();

        assertEquals("RESUME_REQUESTED", entity.getEventType());
        assertEquals("tenant1", entity.getTenantId());
        assertEquals("sus_1", entity.getSuspendId());
        assertEquals("admin", entity.getUpstreamUserId());
    }

    @Test
    void recordResumeDispatched_persists_dispatched_status() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.recordResumeDispatched("tenant1", "sus_1");

        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> captor = ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(repository).save(captor.capture());

        assertEquals("RESUME_DISPATCHED", captor.getValue().getEventType());
    }

    @Test
    void recordResumeFailed_persists_error() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        auditService.recordResumeFailed("tenant1", "sus_1", "Input hash mismatch");

        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> captor = ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(repository).save(captor.capture());

        assertEquals("RESUME_FAILED", captor.getValue().getEventType());
        assertEquals("Input hash mismatch", captor.getValue().getErrorMessage());
    }

    @Test
    void bestEffort_audit_write_failure_does_not_throw() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB unavailable"));

        // This must NOT throw
        assertDoesNotThrow(() ->
                auditService.recordInvokeStarted(token, "fn1", "v1", "hash123")
        );
        assertDoesNotThrow(() ->
                auditService.recordInvokeSuccess(token, "fn1", "v1", "out", 1L)
        );
        assertDoesNotThrow(() ->
                auditService.recordInvokeFailed(token, "fn1", "v1", "ERR", "msg", 1L)
        );
        assertDoesNotThrow(() ->
                auditService.recordToolMessage(token, "t", "fn1", "OK", "msg")
        );
        assertDoesNotThrow(() ->
                auditService.recordResumeRequested("t1", "s1", "u1")
        );
    }

    @Test
    void errorMessage_is_bounded_at_500_chars() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String longMessage = "x".repeat(600);
        auditService.recordInvokeFailed(token, "fn1", "v1", "ERR", longMessage, 1L);

        ArgumentCaptor<BusinessFunctionRuntimeAuditEntity> captor = ArgumentCaptor.forClass(BusinessFunctionRuntimeAuditEntity.class);
        verify(repository).save(captor.capture());

        assertEquals(500, captor.getValue().getErrorMessage().length());
    }

    @Test
    void sha256_produces_deterministic_hash() {
        String hash1 = BusinessFunctionRuntimeAuditService.sha256("{\"orderId\":\"1\"}");
        String hash2 = BusinessFunctionRuntimeAuditService.sha256("{\"orderId\":\"1\"}");
        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length()); // SHA-256 hex is 64 chars
    }

    @Test
    void sha256_null_returns_null() {
        assertNull(BusinessFunctionRuntimeAuditService.sha256(null));
    }
}
