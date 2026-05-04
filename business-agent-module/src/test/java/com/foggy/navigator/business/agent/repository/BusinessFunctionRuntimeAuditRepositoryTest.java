package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessFunctionRuntimeAuditEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.test.context.ContextConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.foggy.navigator.business.agent.service.BusinessFunctionRuntimeAuditService;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = com.foggy.navigator.business.agent.TestApplication.class)
@Import({BusinessFunctionRuntimeAuditService.class, BusinessFunctionRuntimeAuditRepositoryTest.OuterTransactionService.class})
class BusinessFunctionRuntimeAuditRepositoryTest {

    @Service
    public static class OuterTransactionService {
        private final BusinessFunctionRuntimeAuditService auditService;

        public OuterTransactionService(BusinessFunctionRuntimeAuditService auditService) {
            this.auditService = auditService;
        }

        @Transactional
        public void executeAndRollback(String tenantId, String suspendId) {
            auditService.recordResumeFailed(tenantId, suspendId, "Simulated binding mismatch");
            throw new RuntimeException("Simulated outer exception to trigger rollback");
        }
    }

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BusinessFunctionRuntimeAuditRepository repository;

    @Autowired
    private OuterTransactionService outerService;

    @Test
    void should_save_and_find_audit_records() {
        // Arrange
        BusinessFunctionRuntimeAuditEntity audit1 = new BusinessFunctionRuntimeAuditEntity();
        audit1.setAuditId("aud_123");
        audit1.setTenantId("tenantA");
        audit1.setTaskId("task1");
        audit1.setSessionId("session1");
        audit1.setEventType("INVOKE_STARTED");

        BusinessFunctionRuntimeAuditEntity audit2 = new BusinessFunctionRuntimeAuditEntity();
        audit2.setAuditId("aud_456");
        audit2.setTenantId("tenantA");
        audit2.setTaskId("task1");
        audit2.setSessionId("session1");
        audit2.setEventType("INVOKE_SUCCESS");
        audit2.setOutputHash("outhash");
        audit2.setDurationMs(150L);

        BusinessFunctionRuntimeAuditEntity audit3 = new BusinessFunctionRuntimeAuditEntity();
        audit3.setAuditId("aud_789");
        audit3.setTenantId("tenantB");
        audit3.setTaskId("task2");
        audit3.setEventType("INVOKE_STARTED");

        // Act
        entityManager.persist(audit1);
        entityManager.persist(audit2);
        entityManager.persist(audit3);
        entityManager.flush();

        // Assert
        List<BusinessFunctionRuntimeAuditEntity> tenantATask1 = repository.findByTenantIdAndTaskIdOrderByCreatedAtAsc("tenantA", "task1");
        assertThat(tenantATask1).hasSize(2);
        assertThat(tenantATask1.get(0).getAuditId()).isEqualTo("aud_123");
        assertThat(tenantATask1.get(1).getAuditId()).isEqualTo("aud_456");
        assertThat(tenantATask1.get(1).getOutputHash()).isEqualTo("outhash");
        assertThat(tenantATask1.get(1).getDurationMs()).isEqualTo(150L);

        List<BusinessFunctionRuntimeAuditEntity> sessionRecords = repository.findByTenantIdAndSessionIdOrderByCreatedAtAsc("tenantA", "session1");
        assertThat(sessionRecords).hasSize(2);

        List<BusinessFunctionRuntimeAuditEntity> tenantBTask2 = repository.findByTenantIdAndTaskIdOrderByCreatedAtAsc("tenantB", "task2");
        assertThat(tenantBTask2).hasSize(1);
        assertThat(tenantBTask2.get(0).getAuditId()).isEqualTo("aud_789");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void should_persist_audit_record_even_when_outer_transaction_rolls_back() {
        // Act
        try {
            outerService.executeAndRollback("tenant-tx-test", "sus-999");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("Simulated outer exception to trigger rollback");
        }

        // Assert
        List<BusinessFunctionRuntimeAuditEntity> records = repository.findByTenantIdAndSuspendIdOrderByCreatedAtAsc("tenant-tx-test", "sus-999");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getEventType()).isEqualTo("RESUME_FAILED");
        assertThat(records.get(0).getErrorMessage()).isEqualTo("Simulated binding mismatch");
    }
}
