package com.foggy.navigator.codex.worker.repository;

import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Codex 模块本地目录授权绑定 Repository。
 */
public interface CodexAgentDirectoryBindingRepository extends JpaRepository<AgentDirectoryBindingEntity, Long> {

    List<AgentDirectoryBindingEntity> findByDirectoryId(String directoryId);
}
