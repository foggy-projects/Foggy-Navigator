package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.SharingKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 共享密钥 Repository
 */
@Repository
public interface SharingKeyRepository extends JpaRepository<SharingKeyEntity, String> {

    /** 通过共享密钥查找（外部调用验证用） */
    Optional<SharingKeyEntity> findBySharingKey(String sharingKey);

    /** 列出某用户的所有共享密钥 */
    List<SharingKeyEntity> findByOwnerUserIdOrderByCreatedAtDesc(String ownerUserId);

    /** 查找某 Agent 的所有共享密钥 */
    List<SharingKeyEntity> findByAgentId(String agentId);
}
