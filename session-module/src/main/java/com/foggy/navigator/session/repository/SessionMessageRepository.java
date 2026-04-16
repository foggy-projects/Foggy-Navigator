package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.SessionMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, String> {

    List<SessionMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<SessionMessageEntity> findTop50BySessionIdOrderByCreatedAtDesc(String sessionId);

    List<SessionMessageEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    Optional<SessionMessageEntity> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);

    long countBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);

    // ── Open API: cursor 分页查询 ──

    /** 按 taskId 查询消息（首次，cursor 为空） */
    List<SessionMessageEntity> findByTaskIdOrderByCreatedAtAsc(String taskId, Pageable pageable);

    /** 按 taskId + createdAt cursor 查询增量消息（不含 cursor 所指消息） */
    @Query("SELECT m FROM SessionMessageEntity m WHERE m.taskId = :taskId AND m.createdAt > :afterTime ORDER BY m.createdAt ASC")
    List<SessionMessageEntity> findByTaskIdAfterTime(String taskId, java.time.LocalDateTime afterTime, Pageable pageable);

    /** 按 sessionId 分页查询（升序，用于会话消息列表） */
    List<SessionMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId, Pageable pageable);

    /** 按 sessionId + createdAt cursor 查询增量消息 */
    @Query("SELECT m FROM SessionMessageEntity m WHERE m.sessionId = :sessionId AND m.createdAt > :afterTime ORDER BY m.createdAt ASC")
    List<SessionMessageEntity> findBySessionIdAfterTime(String sessionId, java.time.LocalDateTime afterTime, Pageable pageable);
}
