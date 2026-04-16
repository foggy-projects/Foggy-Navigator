package com.foggy.navigator.langgraph.worker.repository;

import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LanggraphWorkerRepository extends JpaRepository<LanggraphWorkerEntity, Long> {

    Optional<LanggraphWorkerEntity> findByWorkerId(String workerId);

    Optional<LanggraphWorkerEntity> findByWorkerIdAndUserId(String workerId, String userId);

    List<LanggraphWorkerEntity> findByUserIdOrderByCreatedAtDesc(String userId);
}
