package com.foggy.navigator.metadata.query.config.repository;

import com.foggy.navigator.common.entity.DatasourceConfigEntity;
import com.foggy.navigator.common.enums.ConfigItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 数据源配置 Repository
 */
@Repository
public interface DatasourceConfigRepository extends JpaRepository<DatasourceConfigEntity, String> {

    /**
     * 根据租户ID查找数据源配置
     */
    List<DatasourceConfigEntity> findByTenantId(String tenantId);

    /**
     * 根据租户ID和状态查找
     */
    List<DatasourceConfigEntity> findByTenantIdAndStatus(String tenantId, ConfigItemStatus status);

    /**
     * 根据租户ID查找最新的数据源配置
     */
    Optional<DatasourceConfigEntity> findFirstByTenantIdOrderByCreatedAtDesc(String tenantId);

    /**
     * 更新配置状态
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DatasourceConfigEntity d SET d.status = :status, d.updatedAt = CURRENT_TIMESTAMP WHERE d.id = :id")
    int updateStatus(@Param("id") String id, @Param("status") ConfigItemStatus status);

    /**
     * 更新连接状态
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DatasourceConfigEntity d SET d.connectionValid = :valid, d.updatedAt = CURRENT_TIMESTAMP WHERE d.id = :id")
    int updateConnectionValid(@Param("id") String id, @Param("valid") boolean valid);
}
