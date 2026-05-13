package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.SkillBundleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SkillBundleRepository extends JpaRepository<SkillBundleEntity, Long> {
    Optional<SkillBundleEntity> findByTenantIdAndClientAppIdAndScopeAndAccountIdAndSkillId(
            String tenantId,
            String clientAppId,
            String scope,
            String accountId,
            String skillId);
}
