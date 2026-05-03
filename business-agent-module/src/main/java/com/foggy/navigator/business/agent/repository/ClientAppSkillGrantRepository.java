package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.ClientAppSkillGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientAppSkillGrantRepository extends JpaRepository<ClientAppSkillGrantEntity, Long> {
    Optional<ClientAppSkillGrantEntity> findByTenantIdAndClientAppIdAndSkillId(String tenantId, String clientAppId, String skillId);
    List<ClientAppSkillGrantEntity> findByTenantIdAndClientAppId(String tenantId, String clientAppId);
}
