package com.foggy.navigator.common.authorization;

import com.foggy.navigator.common.repository.AuthorizationManagementTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationManagementTokenRepositoryContractTest {

    @Test
    void securityActionCompareAndSetBindsEveryReplayRelevantField() throws Exception {
        Method method = AuthorizationManagementTokenRepository.class.getMethod(
                "consumeSecurityActionAtomically",
                String.class, String.class, String.class, String.class, String.class, String.class,
                Integer.class, String.class, String.class, java.time.LocalDateTime.class, java.time.LocalDateTime.class);
        Query query = method.getAnnotation(Query.class);

        assertNotNull(query);
        String normalized = query.value().replaceAll("\\s+", " ");
        for (String requiredClause : new String[]{
                "token.tokenId = :tokenId",
                "token.tokenHash = :tokenHash",
                "token.tokenReference = :tokenReference",
                "token.navigatorInstanceId = :navigatorInstanceId",
                "token.environmentProfile = :environmentProfile",
                "token.purpose = :purpose",
                "token.credentialGeneration = :credentialGeneration",
                "token.status = :activeStatus",
                "token.consumedAt is null",
                "token.expiresAt > :now",
                "token.status = :consumedStatus",
                "token.consumedAt = :consumedAt"}) {
            assertTrue(normalized.contains(requiredClause), () -> "missing CAS clause: " + requiredClause);
        }
    }
}
