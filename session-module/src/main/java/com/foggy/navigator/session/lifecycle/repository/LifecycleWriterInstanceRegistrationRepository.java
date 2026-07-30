package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterInstanceRegistrationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LifecycleWriterInstanceRegistrationRepository
        extends JpaRepository<LifecycleWriterInstanceRegistrationEntity, String> {
}
