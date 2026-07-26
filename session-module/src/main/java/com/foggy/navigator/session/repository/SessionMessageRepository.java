package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.SessionMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, String> {

    List<SessionMessageEntity> findBySessionIdOrderByCreatedAtAscIdAsc(String sessionId);

    List<SessionMessageEntity> findTop50BySessionIdOrderByCreatedAtDescIdDesc(String sessionId);

    List<SessionMessageEntity> findBySessionIdOrderByCreatedAtDescIdDesc(String sessionId, Pageable pageable);

    Optional<SessionMessageEntity> findFirstBySessionIdOrderByCreatedAtDescIdDesc(String sessionId);

    List<SessionMessageEntity> findBySessionIdInAndRoleOrderBySessionIdAscCreatedAtAscIdAsc(
            Collection<String> sessionIds, String role);

    long countBySessionId(String sessionId);

    long countByTaskId(String taskId);

    Optional<SessionMessageEntity> findFirstByTaskIdOrderByCreatedAtDescIdDesc(String taskId);

    List<SessionMessageEntity> findBySessionIdAndTaskIdAndRoleOrderByCreatedAtDescIdDesc(
            String sessionId, String taskId, String role);

    void deleteBySessionId(String sessionId);

    // ── Open API: cursor 分页查询 ──

    /** 按 taskId 查询消息（首次，cursor 为空） */
    List<SessionMessageEntity> findByTaskIdOrderByCreatedAtAscIdAsc(String taskId, Pageable pageable);

    /** 按 taskId 查询最近消息（用于 evidence 快照） */
    List<SessionMessageEntity> findByTaskIdOrderByCreatedAtDescIdDesc(String taskId, Pageable pageable);

    /** 按 taskId + (createdAt, id) cursor 查询增量消息（不含 cursor 所指消息） */
    @Query("""
            SELECT m FROM SessionMessageEntity m
            WHERE m.taskId = :taskId
              AND (m.createdAt > :afterTime OR (m.createdAt = :afterTime AND m.id > :cursorId))
            ORDER BY m.createdAt ASC, m.id ASC
            """)
    List<SessionMessageEntity> findByTaskIdAfterCursor(
            String taskId,
            java.time.LocalDateTime afterTime,
            String cursorId,
            Pageable pageable);

    /** 按 sessionId 分页查询（升序，用于会话消息列表） */
    List<SessionMessageEntity> findBySessionIdOrderByCreatedAtAscIdAsc(String sessionId, Pageable pageable);

    /** 按 sessionId + (createdAt, id) cursor 查询增量消息 */
    @Query("""
            SELECT m FROM SessionMessageEntity m
            WHERE m.sessionId = :sessionId
              AND (m.createdAt > :afterTime OR (m.createdAt = :afterTime AND m.id > :cursorId))
            ORDER BY m.createdAt ASC, m.id ASC
            """)
    List<SessionMessageEntity> findBySessionIdAfterCursor(
            String sessionId,
            java.time.LocalDateTime afterTime,
            String cursorId,
            Pageable pageable);
}
