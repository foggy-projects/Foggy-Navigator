package com.foggy.navigator.codereview.repository;

import com.foggy.navigator.codereview.model.entity.CodeReviewRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeReviewRecordRepository extends JpaRepository<CodeReviewRecordEntity, Long> {

    Optional<CodeReviewRecordEntity> findByRecordId(String recordId);

    Page<CodeReviewRecordEntity> findByConfigIdOrderByCreatedAtDesc(String configId, Pageable pageable);

    /** 去重检查：同一 MR 是否有 PENDING 或 RUNNING 的记录 */
    List<CodeReviewRecordEntity> findByGitlabProjectIdAndMrIidAndStatusIn(
            Long gitlabProjectId, Long mrIid, List<String> statuses);
}
