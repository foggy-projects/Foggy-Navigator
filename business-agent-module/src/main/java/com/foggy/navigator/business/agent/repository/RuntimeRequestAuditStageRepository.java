package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.RuntimeRequestAuditStageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RuntimeRequestAuditStageRepository extends JpaRepository<RuntimeRequestAuditStageEntity, Long> {
    List<RuntimeRequestAuditStageEntity> findByClientRequestIdOrderByOccurredAtAscIdAsc(String clientRequestId);
    void deleteByClientRequestIdIn(Collection<String> clientRequestIds);
}
