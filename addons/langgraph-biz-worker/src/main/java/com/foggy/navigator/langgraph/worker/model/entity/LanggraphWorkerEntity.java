package com.foggy.navigator.langgraph.worker.model.entity;

import com.foggy.navigator.common.entity.BaseWorkerEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * LangGraph Biz Worker registration — extends BaseWorkerEntity.
 * <p>
 * Phase 1: no extra fields beyond base. Future phases may add
 * skill registry config, checkpoint storage config, etc.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "langgraph_workers", indexes = {
        @Index(name = "idx_lgw_user_id", columnList = "userId")
})
public class LanggraphWorkerEntity extends BaseWorkerEntity {

    /** Provider-specific extension config (JSON, reserved for future use) */
    @Column(columnDefinition = "TEXT")
    private String providerExt;
}
