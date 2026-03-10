package com.foggy.navigator.auth.repository;

import com.foggy.navigator.common.entity.UserEntity;
import com.foggy.navigator.common.enums.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户Repository
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {

    /**
     * 根据用户名查找用户
     */
    Optional<UserEntity> findByUsername(String username);

    /**
     * 根据邮箱查找用户
     */
    Optional<UserEntity> findByEmail(String email);

    /**
     * 根据租户ID查找用户列表
     */
    List<UserEntity> findByTenantId(String tenantId);

    /**
     * 根据租户ID和状态查找用户列表
     */
    List<UserEntity> findByTenantIdAndStatus(String tenantId, UserStatus status);

    /**
     * 根据状态查找所有用户（不区分租户）
     */
    List<UserEntity> findByStatusNot(UserStatus status);

    /**
     * 检查用户名是否存在
     */
    boolean existsByUsername(String username);

    /**
     * 检查邮箱是否存在
     */
    boolean existsByEmail(String email);
}
