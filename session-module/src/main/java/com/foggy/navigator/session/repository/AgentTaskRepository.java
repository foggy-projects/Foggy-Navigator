package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.AgentTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTaskEntity, String> {

    List<AgentTaskEntity> findByParentSessionId(String parentSessionId);

    Optional<AgentTaskEntity> findByTaskId(String taskId);

    Optional<AgentTaskEntity> findByExternalTaskIdAndTaskType(String externalTaskId, String taskType);

    List<AgentTaskEntity> findByUserIdAndStatusIn(String userId, List<String> statuses);
}
