package com.foggy.navigator.claude.worker.repository;

import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkingDirectoryRepository extends JpaRepository<WorkingDirectoryEntity, Long> {

    Optional<WorkingDirectoryEntity> findByDirectoryIdAndUserId(String directoryId, String userId);

    List<WorkingDirectoryEntity> findByWorkerIdAndUserIdOrderByProjectNameAsc(String workerId, String userId);

    Optional<WorkingDirectoryEntity> findByWorkerIdAndPathAndUserId(String workerId, String path, String userId);

    List<WorkingDirectoryEntity> findByWorkerIdAndUserIdAndDirectoryTypeOrderByProjectNameAsc(
            String workerId, String userId, String directoryType);

    List<WorkingDirectoryEntity> findByParentProjectIdAndUserIdOrderByProjectNameAsc(
            String parentProjectId, String userId);
}
