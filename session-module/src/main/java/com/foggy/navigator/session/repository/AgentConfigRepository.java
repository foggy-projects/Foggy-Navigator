package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.AgentConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Agent 配置 Repository
 */
public interface AgentConfigRepository extends JpaRepository<AgentConfigEntity, String> {

    List<AgentConfigEntity> findByStatus(String status);
}
