package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessFunctionSuspensionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessFunctionSuspensionRepository extends JpaRepository<BusinessFunctionSuspensionEntity, Long> {

    Optional<BusinessFunctionSuspensionEntity> findBySuspendId(String suspendId);

}
