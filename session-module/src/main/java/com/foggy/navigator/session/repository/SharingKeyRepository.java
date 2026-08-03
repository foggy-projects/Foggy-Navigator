package com.foggy.navigator.session.repository;

import com.foggy.navigator.common.entity.SharingKeyEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** Serializes one already-authorized SharingKey quota consumption by stable row identity. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sharingKey from SharingKeyEntity sharingKey where sharingKey.id = :id")
    Optional<SharingKeyEntity> findByIdForUpdate(@Param("id") String id);

    /** 列出某用户的所有共享密钥 */
    List<SharingKeyEntity> findByOwnerUserIdOrderByCreatedAtDesc(String ownerUserId);

    /** 查找某 Agent 的所有共享密钥 */
    List<SharingKeyEntity> findByAgentId(String agentId);
}
