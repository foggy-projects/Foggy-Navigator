package com.foggy.navigator.metadata.query.config.repository;

import com.foggy.navigator.common.entity.UserMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 用户记忆 Repository
 */
@Repository
public interface UserMemoryRepository extends JpaRepository<UserMemoryEntity, String> {

    List<UserMemoryEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    long countByUserId(String userId);

    List<UserMemoryEntity> findByUserIdOrderByUpdatedAtAsc(String userId);
}
