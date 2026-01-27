package com.foggy.navigator.metadata.config.repository;

import com.foggy.navigator.common.entity.SemanticLayerConfigEntity;
import com.foggy.navigator.common.enums.ConfigItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 语义层配置 Repository
 */
@Repository
public interface SemanticLayerConfigRepository extends JpaRepository<SemanticLayerConfigEntity, String> {

    /**
     * 根据租户ID查找语义层配置
     */
    List<SemanticLayerConfigEntity> findByTenantId(String tenantId);

    /**
     * 根据数据源ID查找
     */
    List<SemanticLayerConfigEntity> findByDatasourceId(String datasourceId);

    /**
     * 根据租户ID和状态查找
     */
    List<SemanticLayerConfigEntity> findByTenantIdAndStatus(String tenantId, ConfigItemStatus status);

    /**
     * 根据租户ID查找最新的语义层配置
     */
    Optional<SemanticLayerConfigEntity> findFirstByTenantIdOrderByCreatedAtDesc(String tenantId);

    /**
     * 更新配置状态
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SemanticLayerConfigEntity s SET s.status = :status, s.updatedAt = CURRENT_TIMESTAMP WHERE s.id = :id")
    int updateStatus(@Param("id") String id, @Param("status") ConfigItemStatus status);

    /**
     * 更新模型数量
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SemanticLayerConfigEntity s SET s.modelCount = :count, s.updatedAt = CURRENT_TIMESTAMP WHERE s.id = :id")
    int updateModelCount(@Param("id") String id, @Param("count") int count);

    /**
     * 删除指定数据源的所有语义层配置
     */
    void deleteByDatasourceId(String datasourceId);
}
