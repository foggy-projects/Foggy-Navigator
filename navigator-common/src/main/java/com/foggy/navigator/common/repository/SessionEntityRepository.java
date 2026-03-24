package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SessionEntityRepository extends JpaRepository<SessionEntity, String> {

    Optional<SessionEntity> findByIdAndUserId(String id, String userId);

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

    List<SessionEntity> findByInteractionStateIn(List<String> states);

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
