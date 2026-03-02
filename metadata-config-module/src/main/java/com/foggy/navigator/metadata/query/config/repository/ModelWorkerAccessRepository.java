package com.foggy.navigator.metadata.query.config.repository;

import com.foggy.navigator.common.entity.ModelWorkerAccessEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 模型-Worker 访问关联 Repository
 */
@Repository
public interface ModelWorkerAccessRepository extends JpaRepository<ModelWorkerAccessEntity, String> {

    List<ModelWorkerAccessEntity> findByModelConfigId(String modelConfigId);

    List<ModelWorkerAccessEntity> findByWorkerIdAndTenantId(String workerId, String tenantId);

    /**
     * 直接执行 JPQL DELETE，立即写库，避免 JPA 派生删除的延迟 flush
     * 导致后续 INSERT 出现唯一键冲突（uk_mwa_model_worker）。
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ModelWorkerAccessEntity m WHERE m.modelConfigId = :modelConfigId")
    void deleteByModelConfigId(@Param("modelConfigId") String modelConfigId);
}
