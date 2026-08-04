package com.foggy.navigator.workbench.fap.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkbenchFapConversationBindingRepository
        extends JpaRepository<WorkbenchFapConversationBindingEntity, String> {

    Optional<WorkbenchFapConversationBindingEntity> findByConversationIdAndOwnerUserId(
            String conversationId, String ownerUserId);

    Optional<WorkbenchFapConversationBindingEntity> findByOwnerUserIdAndStartRequestId(
            String ownerUserId, String startRequestId);

    List<WorkbenchFapConversationBindingEntity> findTop100ByOwnerUserIdOrderByUpdatedAtDesc(
            String ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select binding
              from WorkbenchFapConversationBindingEntity binding
             where binding.conversationId = :conversationId
               and binding.ownerUserId = :ownerUserId
            """)
    Optional<WorkbenchFapConversationBindingEntity> findOwnedForUpdate(
            @Param("conversationId") String conversationId,
            @Param("ownerUserId") String ownerUserId);
}
