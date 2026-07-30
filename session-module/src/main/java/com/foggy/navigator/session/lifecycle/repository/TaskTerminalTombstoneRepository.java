package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalTombstoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskTerminalTombstoneRepository
        extends JpaRepository<TaskTerminalTombstoneEntity, String> {
}
