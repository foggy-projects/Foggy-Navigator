package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 工作目录 Repository —— Agent 无关，所有 Agent 共享。
 */
public interface WorkingDirectoryRepository extends JpaRepository<WorkingDirectoryEntity, Long> {

    Optional<WorkingDirectoryEntity> findByDirectoryIdAndUserId(String directoryId, String userId);

    List<WorkingDirectoryEntity> findByWorkerIdAndUserIdOrderByProjectNameAsc(String workerId, String userId);

    Optional<WorkingDirectoryEntity> findByWorkerIdAndPathAndUserId(String workerId, String path, String userId);

    List<WorkingDirectoryEntity> findByWorkerIdAndUserIdAndDirectoryTypeOrderByProjectNameAsc(
            String workerId, String userId, String directoryType);

    List<WorkingDirectoryEntity> findByParentProjectIdAndUserIdOrderByProjectNameAsc(
            String parentProjectId, String userId);

    Optional<WorkingDirectoryEntity> findByDirectoryId(String directoryId);

    List<WorkingDirectoryEntity> findByDirectoryIdIn(Collection<String> directoryIds);

    Optional<WorkingDirectoryEntity> findBySourceDirectoryIdAndGitBranch(String sourceDirectoryId, String gitBranch);

    Optional<WorkingDirectoryEntity> findByWorkerIdAndPath(String workerId, String path);

    List<WorkingDirectoryEntity> findByTenantId(String tenantId);

    List<WorkingDirectoryEntity> findByWorkerIdOrderByProjectNameAsc(String workerId);
}
