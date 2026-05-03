package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.SkillFunctionAllowlistEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkillFunctionAllowlistRepository extends JpaRepository<SkillFunctionAllowlistEntity, Long> {
    Optional<SkillFunctionAllowlistEntity> findByTenantIdAndSkillIdAndFunctionId(String tenantId, String skillId, String functionId);
    List<SkillFunctionAllowlistEntity> findByTenantIdAndSkillId(String tenantId, String skillId);
}
