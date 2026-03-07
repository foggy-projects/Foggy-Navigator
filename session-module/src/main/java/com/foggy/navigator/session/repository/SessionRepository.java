package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    List<SessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    List<SessionEntity> findByUserIdAndAgentIdOrderByUpdatedAtDesc(String userId, String agentId);

    List<SessionEntity> findByUserIdAndStatusInOrderByUpdatedAtDesc(String userId, List<String> statuses);

    @Query("SELECT s.id FROM SessionEntity s WHERE s.id IN :ids AND s.summary IS NULL")
    List<String> findIdsWithoutSummary(@Param("ids") List<String> ids);

    @Modifying
    @Query("UPDATE SessionEntity s SET s.summary = :summary, s.summaryGeneratedAt = :generatedAt WHERE s.id = :id")
    void updateSummary(@Param("id") String id, @Param("summary") String summary,
                       @Param("generatedAt") LocalDateTime generatedAt);
}
