package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterInstanceRegistrationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface LifecycleWriterInstanceRegistrationRepository
        extends JpaRepository<LifecycleWriterInstanceRegistrationEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from LifecycleWriterInstanceRegistrationEntity i where i.instanceId = :instanceId")
    Optional<LifecycleWriterInstanceRegistrationEntity> findForUpdate(
            String instanceId);
}
