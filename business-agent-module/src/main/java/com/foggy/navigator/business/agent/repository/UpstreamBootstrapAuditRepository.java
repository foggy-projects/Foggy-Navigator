package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.UpstreamBootstrapAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UpstreamBootstrapAuditRepository extends JpaRepository<UpstreamBootstrapAuditEntity, Long> {
}
