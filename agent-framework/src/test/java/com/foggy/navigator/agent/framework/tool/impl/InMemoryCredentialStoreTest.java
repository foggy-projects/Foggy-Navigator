package com.foggy.navigator.agent.framework.tool.impl;

import com.foggy.navigator.agent.framework.tool.UserToolCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryCredentialStoreTest {

    private InMemoryCredentialStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryCredentialStore();
    }

    @Test
    void save_shouldStoreCredential() {
        UserToolCredential credential = createCredential("user-1", "github-tool");

        store.save(credential);

        UserToolCredential found = store.find("user-1", "github-tool");
        assertNotNull(found);
        assertEquals("user-1", found.getUserId());
        assertEquals("github-tool", found.getToolName());
    }

    @Test
    void save_shouldSetTimestamps() {
        UserToolCredential credential = createCredential("user-1", "tool");

        store.save(credential);

        UserToolCredential found = store.find("user-1", "tool");
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
    }

    @Test
    void save_shouldPreserveCreatedAtOnUpdate() {
        UserToolCredential credential = createCredential("user-1", "tool");
        store.save(credential);
        LocalDateTime originalCreatedAt = store.find("user-1", "tool").getCreatedAt();

        // Update the credential
        credential.setAccessToken("new-token");
        store.save(credential);

        UserToolCredential updated = store.find("user-1", "tool");
        assertEquals(originalCreatedAt, updated.getCreatedAt());
        assertEquals("new-token", updated.getAccessToken());
    }

    @Test
    void find_shouldReturnNullForNonExistent() {
        assertNull(store.find("non-existent", "tool"));
    }

    @Test
    void findByUser_shouldReturnAllUserCredentials() {
        store.save(createCredential("user-1", "tool-a"));
        store.save(createCredential("user-1", "tool-b"));
        store.save(createCredential("user-2", "tool-a"));

        List<UserToolCredential> user1Credentials = store.findByUser("user-1");
        assertEquals(2, user1Credentials.size());

        List<UserToolCredential> user2Credentials = store.findByUser("user-2");
        assertEquals(1, user2Credentials.size());
    }

    @Test
    void delete_shouldRemoveCredential() {
        store.save(createCredential("user-1", "tool"));

        store.delete("user-1", "tool");

        assertNull(store.find("user-1", "tool"));
    }

    @Test
    void isValid_shouldReturnTrueForValidCredential() {
        UserToolCredential credential = UserToolCredential.builder()
                .userId("user-1")
                .toolName("tool")
                .accessToken("token")
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
        store.save(credential);

        assertTrue(store.isValid("user-1", "tool"));
    }

    @Test
    void isValid_shouldReturnFalseForExpiredCredential() {
        UserToolCredential credential = UserToolCredential.builder()
                .userId("user-1")
                .toolName("tool")
                .accessToken("token")
                .expiresAt(LocalDateTime.now().minusHours(1))
                .build();
        store.save(credential);

        assertFalse(store.isValid("user-1", "tool"));
    }

    @Test
    void isValid_shouldReturnFalseForNonExistent() {
        assertFalse(store.isValid("non-existent", "tool"));
    }

    @Test
    void refresh_shouldReturnNull() {
        // MVP阶段不支持刷新
        assertNull(store.refresh("user-1", "tool"));
    }

    private UserToolCredential createCredential(String userId, String toolName) {
        return UserToolCredential.builder()
                .userId(userId)
                .toolName(toolName)
                .accessToken("test-token")
                .build();
    }
}
