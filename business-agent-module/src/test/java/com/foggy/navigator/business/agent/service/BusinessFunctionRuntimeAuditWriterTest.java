package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BusinessFunctionRuntimeAuditEntity;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRuntimeAuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BusinessFunctionRuntimeAuditWriterTest {

    @Test
    void write_flushesAuditRecordInsideRequiresNewTransaction() throws Exception {
        BusinessFunctionRuntimeAuditRepository repository = mock(BusinessFunctionRuntimeAuditRepository.class);
        BusinessFunctionRuntimeAuditWriter writer = new BusinessFunctionRuntimeAuditWriter(repository);
        BusinessFunctionRuntimeAuditEntity entity = new BusinessFunctionRuntimeAuditEntity();

        writer.write(entity);

        verify(repository).saveAndFlush(entity);
        Method method = BusinessFunctionRuntimeAuditWriter.class
                .getMethod("write", BusinessFunctionRuntimeAuditEntity.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }
}
