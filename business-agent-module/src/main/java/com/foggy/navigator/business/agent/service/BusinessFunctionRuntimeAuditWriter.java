package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BusinessFunctionRuntimeAuditEntity;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRuntimeAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the independent transaction used to persist one runtime audit record.
 *
 * <p>The caller deliberately lives in a separate bean so it can catch failures
 * raised by the transaction proxy while flushing or committing this write.
 */
@Service
@RequiredArgsConstructor
public class BusinessFunctionRuntimeAuditWriter {

    private final BusinessFunctionRuntimeAuditRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(BusinessFunctionRuntimeAuditEntity entity) {
        repository.saveAndFlush(entity);
    }
}
