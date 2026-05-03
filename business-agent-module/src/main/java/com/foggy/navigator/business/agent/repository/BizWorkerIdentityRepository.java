package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BizWorkerIdentityRepository extends JpaRepository<BizWorkerIdentityEntity, Long> {

    Optional<BizWorkerIdentityEntity> findByWorkerId(String workerId);
}
