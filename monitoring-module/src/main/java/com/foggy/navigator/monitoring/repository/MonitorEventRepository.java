package com.foggy.navigator.monitoring.repository;

import com.foggy.navigator.monitoring.model.entity.MonitorEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface MonitorEventRepository extends JpaRepository<MonitorEventEntity, Long> {

    Page<MonitorEventEntity> findByServiceAndEventTypeOrderByEventTimeDesc(
            String service, String eventType, Pageable pageable);

    Page<MonitorEventEntity> findByServiceOrderByEventTimeDesc(
            String service, Pageable pageable);

    Page<MonitorEventEntity> findByLevelOrderByEventTimeDesc(
            String level, Pageable pageable);

    Page<MonitorEventEntity> findAllByOrderByEventTimeDesc(Pageable pageable);

    Page<MonitorEventEntity> findByEventTimeAfterOrderByEventTimeDesc(
            LocalDateTime after, Pageable pageable);

    long countByLevelAndEventTimeAfter(String level, LocalDateTime after);
}
