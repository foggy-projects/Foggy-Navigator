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

    Optional<WorkingDirectoryEntity> findByDirectoryId(String directoryId);

    /**
     * 查找已存在的 worktree（相同源目录和分支）
     */
    Optional<WorkingDirectoryEntity> findBySourceDirectoryIdAndGitBranch(String sourceDirectoryId, String gitBranch);

    /**
     * 按租户查询所有工作目录（Open API 用）
     */
    List<WorkingDirectoryEntity> findByTenantId(String tenantId);

    /**
     * 按 Worker 查询所有工作目录（不限用户，Open API 用）
     */
    List<WorkingDirectoryEntity> findByWorkerIdOrderByProjectNameAsc(String workerId);
}
