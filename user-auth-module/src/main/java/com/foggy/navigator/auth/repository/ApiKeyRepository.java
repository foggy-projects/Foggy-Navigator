package com.foggy.navigator.auth.repository;

import com.foggy.navigator.common.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * API Key Repository
 */
@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, String> {

    /**
     * 根据API Key查找
     */
    Optional<ApiKeyEntity> findByApiKey(String apiKey);

    /**
     * 根据用户ID查找所有API Key
     */
    List<ApiKeyEntity> findByUserId(String userId);

    /**
     * 根据用户ID和启用状态查找API Key
     */
    List<ApiKeyEntity> findByUserIdAndEnabled(String userId, Boolean enabled);

    /**
     * 查找已过期的API Key
     */
    List<ApiKeyEntity> findByExpiresAtBefore(LocalDateTime dateTime);

    /**
     * 检查API Key是否存在
     */
    boolean existsByApiKey(String apiKey);
}
