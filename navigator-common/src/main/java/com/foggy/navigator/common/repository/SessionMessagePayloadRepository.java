package com.foggy.navigator.common.repository;

import com.foggy.navigator.common.entity.SessionMessagePayloadEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Database-only access to large session-message payload descriptors. */
public interface SessionMessagePayloadRepository extends JpaRepository<SessionMessagePayloadEntity, String> {

    Optional<SessionMessagePayloadEntity> findByMessageId(String messageId);

    /**
     * Claims an existing descriptor, or the MySQL unique-key/gap range for a
     * new one, within the durable message transaction. This makes a replay
     * inspect the first committed descriptor before it can write a second
     * object to the payload store.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT payload FROM SessionMessagePayloadEntity payload WHERE payload.messageId = :messageId")
    Optional<SessionMessagePayloadEntity> findByMessageIdForUpdate(@Param("messageId") String messageId);
}
