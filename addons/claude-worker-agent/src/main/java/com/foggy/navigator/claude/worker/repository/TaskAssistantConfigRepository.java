package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.claude.worker.model.entity.TaskAssistantConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 任务助手配置仓储
 */
public interface TaskAssistantConfigRepository extends JpaRepository<TaskAssistantConfigEntity, Long> {

    Optional<TaskAssistantConfigEntity> findByUserId(String userId);
}
