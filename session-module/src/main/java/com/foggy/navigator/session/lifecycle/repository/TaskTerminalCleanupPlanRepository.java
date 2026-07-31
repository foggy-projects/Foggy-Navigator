package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;

public interface TaskTerminalCleanupPlanRepository
        extends JpaRepository<TaskTerminalCleanupPlanEntity, TaskTerminalCleanupPlanId> {
    List<TaskTerminalCleanupPlanEntity> findByIdTaskIdOrderByIdParticipant(String taskId);

    @Query("select distinct plan.id.taskId from TaskTerminalCleanupPlanEntity plan "
            + "where plan.applicability = 'REQUIRED' "
            + "and plan.checkpointState <> 'COMPLETED'")
    List<String> findTaskIdsRequiringResume();
}
