package com.foggy.navigator.metadata.query.config.repository;

import com.foggy.navigator.common.entity.LlmModelConfigEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * LLM 模型配置 Repository
 */
@Repository
public interface LlmModelConfigRepository extends JpaRepository<LlmModelConfigEntity, String> {

    List<LlmModelConfigEntity> findByTenantIdOrderByCreatedAtAsc(String tenantId);

    Optional<LlmModelConfigEntity> findByTenantIdAndCategoryAndIsDefaultTrue(String tenantId, LlmModelCategory category);

    List<LlmModelConfigEntity> findByTenantIdAndCategoryOrderByCreatedAtAsc(String tenantId, LlmModelCategory category);

    List<LlmModelConfigEntity> findByTenantIdAndIsDefaultTrue(String tenantId);

    boolean existsByTenantId(String tenantId);
}
