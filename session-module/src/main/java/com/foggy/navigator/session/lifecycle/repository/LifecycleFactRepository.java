package com.foggy.navigator.session.lifecycle.repository;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleFactEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LifecycleFactRepository extends JpaRepository<LifecycleFactEntity, String> {
    List<LifecycleFactEntity> findByAggregateTypeAndAggregateIdOrderBySourceSequenceAsc(
            String aggregateType, String aggregateId);
}
