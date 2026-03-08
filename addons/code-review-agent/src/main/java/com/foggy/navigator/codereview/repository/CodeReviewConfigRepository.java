package com.foggy.navigator.codereview.repository;

import com.foggy.navigator.codereview.model.entity.CodeReviewConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeReviewConfigRepository extends JpaRepository<CodeReviewConfigEntity, String> {

    List<CodeReviewConfigEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<CodeReviewConfigEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<CodeReviewConfigEntity> findByGitlabProjectIdAndIsActiveTrue(Long gitlabProjectId);

    List<CodeReviewConfigEntity> findByGitlabProjectId(Long gitlabProjectId);
}
