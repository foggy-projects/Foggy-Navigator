package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SkillRepository extends JpaRepository<SkillEntity, Long> {
    Optional<SkillEntity> findByTenantIdAndSkillId(String tenantId, String skillId);
}
