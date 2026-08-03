package com.foggy.navigator.session.command.repository;

import com.foggy.navigator.session.command.persistence.CommandOnceReceiptEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CommandOnceReceiptRepository
        extends Repository<CommandOnceReceiptEntity, String> {

    <S extends CommandOnceReceiptEntity> S saveAndFlush(S entity);

    Optional<CommandOnceReceiptEntity> findByClientRequestId(String clientRequestId);

    Optional<CommandOnceReceiptEntity> findByEffectAttemptId(String effectAttemptId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select receipt from CommandOnceReceiptEntity receipt "
            + "where receipt.receiptId = :receiptId")
    Optional<CommandOnceReceiptEntity> findByReceiptIdForUpdate(
            @Param("receiptId") String receiptId);
}
