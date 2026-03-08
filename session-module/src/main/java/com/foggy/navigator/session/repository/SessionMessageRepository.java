package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.SessionMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, String> {

    List<SessionMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<SessionMessageEntity> findTop50BySessionIdOrderByCreatedAtDesc(String sessionId);

    long countBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);
}
