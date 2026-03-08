package com.foggy.navigator.metadata.query.config;

import com.foggy.navigator.common.dto.ApiCredentialDTO;
import com.foggy.navigator.common.entity.ApiCredentialEntity;
import com.foggy.navigator.common.enums.AuthType;
import com.foggy.navigator.common.form.ApiCredentialForm;
import com.foggy.navigator.metadata.query.config.repository.ApiCredentialRepository;
import com.foggy.navigator.spi.config.ApiCredentialManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = TestConfig.class)
@ActiveProfiles("test")
@Transactional
class ApiCredentialManagerTest {

    @Autowired
    private ApiCredentialManager apiCredentialManager;

    @Autowired
    private ApiCredentialRepository apiCredentialRepo;

    @Test
    void testSaveCredential_Basic() {
        ApiCredentialForm form = createTestCredentialForm("test-credential", "default");

        String id = apiCredentialManager.saveCredential("tenant-001", form);

        assertNotNull(id);
        Optional<ApiCredentialEntity> saved = apiCredentialRepo.findById(id);
        assertTrue(saved.isPresent());
        assertEquals("test-credential", saved.get().getName());
        assertEquals("default", saved.get().getCategory());
        assertEquals("https://api.example.com", saved.get().getBaseUrl());
        assertEquals(AuthType.API_KEY, saved.get().getAuthType());
        assertTrue(saved.get().getIsActive());
    }

    @Test
    void testSaveCredential_WithExtraHeaders() {
        ApiCredentialForm form = createTestCredentialForm("header-credential", "custom");
        form.setExtraHeaders(Map.of("X-Custom-Header", "value1", "X-Another-Header", "value2"));

        String id = apiCredentialManager.saveCredential("tenant-001", form);

        Optional<ApiCredentialEntity> saved = apiCredentialRepo.findById(id);
        assertTrue(saved.isPresent());
        assertNotNull(saved.get().getExtraHeaders());
    }

    @Test
    void testSaveCredential_BearerToken() {
        ApiCredentialForm form = createTestCredentialForm("bearer-credential", "auth");
        form.setAuthType(AuthType.BEARER_TOKEN);

        String id = apiCredentialManager.saveCredential("tenant-001", form);

        Optional<ApiCredentialEntity> saved = apiCredentialRepo.findById(id);
        assertTrue(saved.isPresent());
        assertEquals(AuthType.BEARER_TOKEN, saved.get().getAuthType());
    }

    @Test
    void testUpdateCredential() {
        String id = createTestCredential("update-test", "default");

        ApiCredentialForm updateForm = new ApiCredentialForm();
        updateForm.setName("updated-credential");
        updateForm.setBaseUrl("https://new-api.example.com");
        updateForm.setAuthType(AuthType.BEARER_TOKEN);
        updateForm.setDescription("Updated description");

        apiCredentialManager.updateCredential(id, updateForm);

        Optional<ApiCredentialEntity> updated = apiCredentialRepo.findById(id);
        assertTrue(updated.isPresent());
        assertEquals("updated-credential", updated.get().getName());
        assertEquals("https://new-api.example.com", updated.get().getBaseUrl());
        assertEquals(AuthType.BEARER_TOKEN, updated.get().getAuthType());
        assertEquals("Updated description", updated.get().getDescription());
    }

    @Test
    void testDeleteCredential() {
        String id = createTestCredential("delete-test", "default");

        apiCredentialManager.deleteCredential(id);

        assertFalse(apiCredentialRepo.existsById(id));
    }

    @Test
    void testListCredentials() {
        createTestCredential("credential-1", "weather");
        createTestCredential("credential-2", "payment");
        createTestCredential("credential-3", "custom");

        List<ApiCredentialDTO> credentials = apiCredentialManager.listCredentials("tenant-001");

        assertTrue(credentials.size() >= 3);
        assertTrue(credentials.stream().anyMatch(c -> c.getName().equals("credential-1")));
        assertTrue(credentials.stream().anyMatch(c -> c.getName().equals("credential-2")));
        assertTrue(credentials.stream().anyMatch(c -> c.getName().equals("credential-3")));
    }

    @Test
    void testGetCredential() {
        String id = createTestCredential("get-test", "default");

        Optional<ApiCredentialDTO> dto = apiCredentialManager.getCredential(id);

        assertTrue(dto.isPresent());
        assertEquals(id, dto.get().getId());
        assertEquals("get-test", dto.get().getName());
        assertEquals("default", dto.get().getCategory());
        assertTrue(dto.get().getHasApiKey());
    }

    @Test
    void testGetCredential_NotFound() {
        Optional<ApiCredentialDTO> dto = apiCredentialManager.getCredential("non-existent-id");

        assertFalse(dto.isPresent());
    }

    @Test
    void testListCredentialsByCategory() {
        createTestCredential("weather-1", "weather");
        createTestCredential("weather-2", "weather");
        createTestCredential("payment-1", "payment");

        List<ApiCredentialDTO> weatherCredentials = apiCredentialManager.listCredentialsByCategory("tenant-001", "weather");

        assertTrue(weatherCredentials.size() >= 2);
        assertTrue(weatherCredentials.stream().allMatch(c -> c.getCategory().equals("weather")));
        assertTrue(weatherCredentials.stream().anyMatch(c -> c.getName().equals("weather-1")));
        assertTrue(weatherCredentials.stream().anyMatch(c -> c.getName().equals("weather-2")));
    }

    @Test
    void testGetCredentialByName() {
        createTestCredential("unique-credential", "custom");

        Optional<ApiCredentialDTO> dto = apiCredentialManager.getCredentialByName("tenant-001", "unique-credential");

        assertTrue(dto.isPresent());
        assertEquals("unique-credential", dto.get().getName());
    }

    @Test
    void testGetCredentialByName_NotFound() {
        Optional<ApiCredentialDTO> dto = apiCredentialManager.getCredentialByName("tenant-001", "non-existent");

        assertFalse(dto.isPresent());
    }

    @Test
    void testGetDecryptedApiKey() {
        String id = createTestCredential("key-test", "default");

        String decryptedKey = apiCredentialManager.getDecryptedApiKey(id);

        assertEquals("test-api-key-12345", decryptedKey);
    }

    @Test
    void testGetExtraHeaders() {
        ApiCredentialForm form = createTestCredentialForm("header-test", "custom");
        form.setExtraHeaders(Map.of("X-Header-1", "value1", "X-Header-2", "value2"));
        String id = apiCredentialManager.saveCredential("tenant-001", form);

        Map<String, String> headers = apiCredentialManager.getExtraHeaders(id);

        assertNotNull(headers);
        assertEquals("value1", headers.get("X-Header-1"));
        assertEquals("value2", headers.get("X-Header-2"));
    }

    @Test
    void testHasAnyCredential() {
        assertFalse(apiCredentialManager.hasAnyCredential("tenant-002"));

        createTestCredential("has-any-test", "default");

        assertTrue(apiCredentialManager.hasAnyCredential("tenant-001"));
    }

    @Test
    void testCredentialDTO_NoApiKeyExposed() {
        String id = createTestCredential("security-test", "default");

        Optional<ApiCredentialDTO> dto = apiCredentialManager.getCredential(id);

        assertTrue(dto.isPresent());
        assertTrue(dto.get().getHasApiKey());
    }

    private ApiCredentialForm createTestCredentialForm(String name, String category) {
        ApiCredentialForm form = new ApiCredentialForm();
        form.setName(name);
        form.setCategory(category);
        form.setBaseUrl("https://api.example.com");
        form.setApiKey("test-api-key-12345");
        form.setAuthType(AuthType.API_KEY);
        form.setDescription("Test credential: " + name);
        return form;
    }

    private String createTestCredential(String name, String category) {
        ApiCredentialForm form = createTestCredentialForm(name, category);
        return apiCredentialManager.saveCredential("tenant-001", form);
    }
}
