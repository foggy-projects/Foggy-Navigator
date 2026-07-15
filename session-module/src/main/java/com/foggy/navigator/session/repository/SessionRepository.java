package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    @Query("SELECT s FROM SessionEntity s " +
           "WHERE s.userId = :userId " +
           "AND s.deletedAt IS NULL " +
           "AND s.status <> 'DELETED' " +
           "ORDER BY s.updatedAt DESC")
    List<SessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    @Query("SELECT s FROM SessionEntity s " +
           "WHERE s.userId = :userId " +
           "AND s.tenantId = :tenantId " +
           "AND s.deletedAt IS NULL " +
           "AND s.status <> 'DELETED' " +
           "ORDER BY s.updatedAt DESC")
    List<SessionEntity> findByUserIdAndTenantIdOrderByUpdatedAtDesc(@Param("userId") String userId,
                                                                    @Param("tenantId") String tenantId);

    @Query("SELECT s FROM SessionEntity s " +
           "WHERE s.userId = :userId " +
           "AND s.agentId = :agentId " +
           "AND s.deletedAt IS NULL " +
           "AND s.status <> 'DELETED' " +
           "ORDER BY s.updatedAt DESC")
    List<SessionEntity> findByUserIdAndAgentIdOrderByUpdatedAtDesc(String userId, String agentId);

    @Query("SELECT s FROM SessionEntity s " +
           "WHERE s.userId = :userId " +
           "AND s.tenantId = :tenantId " +
           "AND s.agentId = :agentId " +
           "AND s.deletedAt IS NULL " +
           "AND s.status <> 'DELETED' " +
           "ORDER BY s.updatedAt DESC")
    List<SessionEntity> findByUserIdAndTenantIdAndAgentIdOrderByUpdatedAtDesc(
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("agentId") String agentId);

    @Query("SELECT s FROM SessionEntity s " +
           "WHERE s.userId = :userId " +
           "AND s.status IN :statuses " +
           "AND s.deletedAt IS NULL " +
           "AND s.status <> 'DELETED' " +
           "ORDER BY s.updatedAt DESC")
    List<SessionEntity> findByUserIdAndStatusInOrderByUpdatedAtDesc(String userId, List<String> statuses);

    Optional<SessionEntity> findByIdAndUserId(String id, String userId);

    Optional<SessionEntity> findByIdAndUserIdAndTenantId(String id, String userId, String tenantId);

    @Query("SELECT s FROM SessionEntity s " +
           "WHERE s.id = :id " +
           "AND s.userId = :userId " +
           "AND (s.tenantId IS NULL OR TRIM(s.tenantId) = '')")
    Optional<SessionEntity> findTenantlessByIdAndUserId(@Param("id") String id,
                                                        @Param("userId") String userId);

    @Query("SELECT s FROM SessionEntity s " +
           "WHERE s.userId = :userId " +
           "AND s.parentSessionId = :parentSessionId " +
           "AND s.deletedAt IS NULL " +
           "AND s.status <> 'DELETED' " +
           "ORDER BY s.updatedAt DESC")
    List<SessionEntity> findActiveChildrenByParentSessionId(@Param("userId") String userId,
                                                            @Param("parentSessionId") String parentSessionId);

    @Query("SELECT s.id FROM SessionEntity s " +
           "WHERE s.userId = :userId AND s.interactionState = :state AND s.deletedAt IS NULL")
    List<String> findSessionIdsByInteractionState(@Param("userId") String userId,
                                                  @Param("state") String state);

    @Query("SELECT s.id FROM SessionEntity s " +
           "WHERE s.userId = :userId AND s.interactionState IN :states AND s.deletedAt IS NULL")
    List<String> findSessionIdsByInteractionStateIn(@Param("userId") String userId,
                                                    @Param("states") List<String> states);

    @Query("SELECT s.id FROM SessionEntity s " +
           "WHERE s.interactionState IN :states AND s.deletedAt IS NULL")
    List<String> findSessionIdsByStates(@Param("states") List<String> states);

    @Query("SELECT s FROM SessionEntity s " +
           "WHERE s.interactionState IN :states " +
           "AND s.deletedAt IS NULL " +
           "AND s.status <> 'DELETED'")
    List<SessionEntity> findByInteractionStateIn(@Param("states") List<String> states);

    @Query("SELECT s.id FROM SessionEntity s " +
           "WHERE s.userId = :userId AND s.deletedAt IS NULL " +
           "AND LOWER(COALESCE(s.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<String> findSessionIdsByTitleKeyword(@Param("userId") String userId,
                                              @Param("keyword") String keyword);

    @Query("SELECT s.id FROM SessionEntity s " +
           "WHERE s.userId = :userId AND s.deletedAt IS NULL " +
           "AND LOWER(COALESCE(s.tagsJson, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<String> findSessionIdsByTagKeyword(@Param("userId") String userId,
                                            @Param("keyword") String keyword);
}
