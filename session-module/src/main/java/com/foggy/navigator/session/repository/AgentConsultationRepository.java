package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.AgentConsultationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentConsultationRepository extends JpaRepository<AgentConsultationEntity, String> {

    List<AgentConsultationEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<AgentConsultationEntity> findByUserIdOrderByCreatedAtDesc(String userId);
}
