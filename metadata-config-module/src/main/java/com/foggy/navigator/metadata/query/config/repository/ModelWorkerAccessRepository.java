package com.foggy.navigator.metadata.query.config.repository;

import com.foggy.navigator.common.entity.ModelWorkerAccessEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 模型-Worker 访问关联 Repository
 */
@Repository
public interface ModelWorkerAccessRepository extends JpaRepository<ModelWorkerAccessEntity, String> {

    List<ModelWorkerAccessEntity> findByModelConfigId(String modelConfigId);

    List<ModelWorkerAccessEntity> findByWorkerIdAndTenantId(String workerId, String tenantId);

    void deleteByModelConfigId(String modelConfigId);
}
