package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessFunctionSuspensionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessFunctionSuspensionRepository extends JpaRepository<BusinessFunctionSuspensionEntity, Long> {

    Optional<BusinessFunctionSuspensionEntity> findBySuspendId(String suspendId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BusinessFunctionSuspensionEntity s where s.suspendId = :suspendId")
    Optional<BusinessFunctionSuspensionEntity> findBySuspendIdForUpdate(@Param("suspendId") String suspendId);

}
