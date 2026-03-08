package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    List<SessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    List<SessionEntity> findByUserIdAndAgentIdOrderByUpdatedAtDesc(String userId, String agentId);

    List<SessionEntity> findByUserIdAndStatusInOrderByUpdatedAtDesc(String userId, List<String> statuses);
}
