package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.NativeSubtaskStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface NativeSubtaskStateRepository extends JpaRepository<NativeSubtaskStateEntity, Long> {

    Optional<NativeSubtaskStateEntity> findByTaskIdAndSubtaskId(String taskId, String subtaskId);

    List<NativeSubtaskStateEntity> findByTaskIdOrderByIdAsc(String taskId);

    @Modifying
    @Transactional
    void deleteByTaskId(String taskId);
}
