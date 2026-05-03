package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BizWorkerPoolMemberRepository extends JpaRepository<BizWorkerPoolMemberEntity, Long> {

    Optional<BizWorkerPoolMemberEntity> findByPoolIdAndWorkerId(String poolId, String workerId);

    List<BizWorkerPoolMemberEntity> findByPoolIdOrderByCreatedAtAsc(String poolId);
}
