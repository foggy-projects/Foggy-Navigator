package com.foggy.navigator.task.assistant.repository;

import com.foggy.navigator.task.assistant.entity.TaskAssistantConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 任务助手配置仓储
 */
public interface TaskAssistantConfigRepository extends JpaRepository<TaskAssistantConfigEntity, Long> {

    Optional<TaskAssistantConfigEntity> findByUserId(String userId);

    List<TaskAssistantConfigEntity> findAllByEnabledTrue();
}
