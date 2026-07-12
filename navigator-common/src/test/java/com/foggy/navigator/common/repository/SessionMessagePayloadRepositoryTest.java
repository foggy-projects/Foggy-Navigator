package com.foggy.navigator.common.repository;

import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMessagePayloadRepositoryTest {

    @Test
    void locksStableMessageIdentityBeforePayloadStoreWrite() throws Exception {
        Method method = SessionMessagePayloadRepository.class
                .getMethod("findByMessageIdForUpdate", String.class);

        Lock lock = method.getAnnotation(Lock.class);
        Query query = method.getAnnotation(Query.class);

        assertNotNull(lock);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
        assertNotNull(query);
        assertTrue(query.value().contains("payload.messageId = :messageId"));
    }
}
