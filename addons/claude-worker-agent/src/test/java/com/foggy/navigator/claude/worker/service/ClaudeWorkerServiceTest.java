package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClientFactory;
import com.foggy.navigator.claude.worker.model.dto.WorkerDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.RegisterWorkerForm;
import com.foggy.navigator.claude.worker.model.form.UpdateWorkerForm;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.common.model.GeminiConfig;
import com.foggy.navigator.common.security.CredentialEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ClaudeWorkerService 单元测试 — L1
 */
@ExtendWith(MockitoExtension.class)
class ClaudeWorkerServiceTest {

    @Mock private ClaudeWorkerRepository workerRepository;
    @Mock private ClaudeWorkerClientFactory clientFactory;
    @Mock private CredentialEncryptor credentialEncryptor;

    @InjectMocks private ClaudeWorkerService service;

    // ---- 注册 Worker ----

    @Test
    void registerWorker_encryptsAuthToken() {
        RegisterWorkerForm form = new RegisterWorkerForm();
        form.setName("My Worker");
        form.setBaseUrl("http://localhost:3031");
        form.setAuthToken("plain-token");

        when(credentialEncryptor.encrypt("plain-token")).thenReturn("enc-token");
        when(workerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerWorker("user-1", "t1", form);

        ArgumentCaptor<ClaudeWorkerEntity> captor = ArgumentCaptor.forClass(ClaudeWorkerEntity.class);
        verify(workerRepository).save(captor.capture());
        assertEquals("enc-token", captor.getValue().getAuthToken());
        assertEquals("user-1", captor.getValue().getUserId());
        assertEquals("My Worker", captor.getValue().getName());
    }

    @Test
    void registerWorker_defaultAuthModeSubscription() {
        RegisterWorkerForm form = new RegisterWorkerForm();
        form.setName("W");
        form.setBaseUrl("http://localhost");
        form.setAuthToken("tk");
        form.setAuthMode(null);

        when(credentialEncryptor.encrypt(anyString())).thenReturn("enc");
        when(workerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerWorker("u1", "t1", form);

        ArgumentCaptor<ClaudeWorkerEntity> captor = ArgumentCaptor.forClass(ClaudeWorkerEntity.class);
        verify(workerRepository).save(captor.capture());
        assertEquals("SUBSCRIPTION", captor.getValue().getAuthMode());
    }

    @Test
    void registerWorker_withSshCredentials_encryptsSshPassword() {
        RegisterWorkerForm form = new RegisterWorkerForm();
        form.setName("SSH Worker");
        form.setBaseUrl("http://localhost");
        form.setAuthToken("tk");
        form.setSshUsername("ubuntu");
        form.setSshPort(22);
        form.setSshPassword("ssh-pass");

        when(credentialEncryptor.encrypt("tk")).thenReturn("enc-tk");
        when(credentialEncryptor.encrypt("ssh-pass")).thenReturn("enc-ssh");
        when(workerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerWorker("u1", "t1", form);

        ArgumentCaptor<ClaudeWorkerEntity> captor = ArgumentCaptor.forClass(ClaudeWorkerEntity.class);
        verify(workerRepository).save(captor.capture());
        assertEquals("ubuntu", captor.getValue().getSshUsername());
        assertEquals(22, captor.getValue().getSshPort());
        assertEquals("enc-ssh", captor.getValue().getSshPassword());
    }

    @Test
    void registerWorker_withCodexConfig_normalizesBaseUrl() {
        RegisterWorkerForm form = new RegisterWorkerForm();
        form.setName("Codex Worker");
        form.setBaseUrl("http://localhost");
        form.setAuthToken("tk");
        form.setCodexConfig(CodexConfig.builder()
                .baseUrl("http://codex:3032///")
                .authToken("codex-token")
                .model("codex-mini")
                .build());

        when(credentialEncryptor.encrypt("tk")).thenReturn("enc-tk");
        when(credentialEncryptor.encrypt("codex-token")).thenReturn("enc-codex");
        when(workerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.registerWorker("u1", "t1", form);

        ArgumentCaptor<ClaudeWorkerEntity> captor = ArgumentCaptor.forClass(ClaudeWorkerEntity.class);
        verify(workerRepository).save(captor.capture());
        CodexConfig saved = captor.getValue().getCodexConfig();
        assertNotNull(saved);
        assertEquals("http://codex:3032", saved.getBaseUrl()); // trailing slashes stripped
        assertEquals("enc-codex", saved.getAuthToken());
        assertEquals("codex-mini", saved.getModel());
    }

    @Test
    void registerWorker_returnsDTO() {
        RegisterWorkerForm form = new RegisterWorkerForm();
        form.setName("Test");
        form.setBaseUrl("http://test");
        form.setAuthToken("tk");

        when(credentialEncryptor.encrypt(anyString())).thenReturn("enc");
        when(workerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkerDTO dto = service.registerWorker("u1", "t1", form);
        assertNotNull(dto);
        assertEquals("Test", dto.getName());
        assertEquals("http://test", dto.getBaseUrl());
    }

    // ---- 更新 Worker ----

    @Test
    void updateWorker_partialUpdate_onlyChangedFields() {
        ClaudeWorkerEntity entity = buildEntity();
        when(workerRepository.findByWorkerIdAndUserId("w1", "u1")).thenReturn(Optional.of(entity));
        when(workerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateWorkerForm form = new UpdateWorkerForm();
        form.setName("New Name");
        // baseUrl and authToken are null → should not change

        service.updateWorker("u1", "w1", form);

        assertEquals("New Name", entity.getName());
        assertEquals("http://original", entity.getBaseUrl()); // unchanged
    }

    @Test
    void updateWorker_emptyStringSshPassword_clearsField() {
        ClaudeWorkerEntity entity = buildEntity();
        entity.setSshPassword("enc-old-ssh");
        when(workerRepository.findByWorkerIdAndUserId("w1", "u1")).thenReturn(Optional.of(entity));
        when(workerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateWorkerForm form = new UpdateWorkerForm();
        form.setSshPassword(""); // empty → clear

        service.updateWorker("u1", "w1", form);

        assertNull(entity.getSshPassword());
    }

    @Test
    void updateWorker_newAuthToken_encryptsAndClearsCache() {
        ClaudeWorkerEntity entity = buildEntity();
        when(workerRepository.findByWorkerIdAndUserId("w1", "u1")).thenReturn(Optional.of(entity));
        when(credentialEncryptor.encrypt("new-token")).thenReturn("enc-new-token");
        when(workerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateWorkerForm form = new UpdateWorkerForm();
        form.setAuthToken("new-token");

        service.updateWorker("u1", "w1", form);

        assertEquals("enc-new-token", entity.getAuthToken());
        verify(clientFactory).remove("w1");
    }

    @Test
    void updateWorker_clearCodexConfig() {
        ClaudeWorkerEntity entity = buildEntity();
        entity.setCodexConfig(CodexConfig.builder().baseUrl("http://codex").build());
        when(workerRepository.findByWorkerIdAndUserId("w1", "u1")).thenReturn(Optional.of(entity));
        when(workerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateWorkerForm form = new UpdateWorkerForm();
        form.setCodexConfig(CodexConfig.builder().baseUrl("").build()); // blank baseUrl → clear

        service.updateWorker("u1", "w1", form);

        assertNull(entity.getCodexConfig());
    }

    @Test
    void updateWorker_codexConfigWithoutAuthToken_preservesExistingToken() {
        ClaudeWorkerEntity entity = buildEntity();
        entity.setCodexConfig(CodexConfig.builder()
                .baseUrl("http://old-codex")
                .authToken("enc-old-codex")
                .model("old-model")
                .build());
        when(workerRepository.findByWorkerIdAndUserId("w1", "u1")).thenReturn(Optional.of(entity));
        when(workerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateWorkerForm form = new UpdateWorkerForm();
        form.setCodexConfig(CodexConfig.builder()
                .baseUrl("http://new-codex///")
                .model("new-model")
                .build());

        service.updateWorker("u1", "w1", form);

        assertNotNull(entity.getCodexConfig());
        assertEquals("http://new-codex", entity.getCodexConfig().getBaseUrl());
        assertEquals("enc-old-codex", entity.getCodexConfig().getAuthToken());
        assertEquals("new-model", entity.getCodexConfig().getModel());
        verify(credentialEncryptor, never()).encrypt(anyString());
    }

    @Test
    void updateWorker_geminiConfigWithoutAuthToken_preservesExistingToken() {
        ClaudeWorkerEntity entity = buildEntity();
        entity.setGeminiConfig(GeminiConfig.builder()
                .baseUrl("http://old-gemini")
                .authToken("enc-old-gemini")
                .model("old-model")
                .build());
        when(workerRepository.findByWorkerIdAndUserId("w1", "u1")).thenReturn(Optional.of(entity));
        when(workerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateWorkerForm form = new UpdateWorkerForm();
        form.setGeminiConfig(GeminiConfig.builder()
                .baseUrl("http://new-gemini///")
                .model("new-model")
                .build());

        service.updateWorker("u1", "w1", form);

        assertNotNull(entity.getGeminiConfig());
        assertEquals("http://new-gemini", entity.getGeminiConfig().getBaseUrl());
        assertEquals("enc-old-gemini", entity.getGeminiConfig().getAuthToken());
        assertEquals("new-model", entity.getGeminiConfig().getModel());
        verify(credentialEncryptor, never()).encrypt(anyString());
    }

    @Test
    void updateWorker_notFound_throwsException() {
        when(workerRepository.findByWorkerIdAndUserId("w99", "u1")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.updateWorker("u1", "w99", new UpdateWorkerForm()));
    }

    // ---- 删除 Worker ----

    @Test
    void deleteWorker_removesFromRepoAndCache() {
        ClaudeWorkerEntity entity = buildEntity();
        when(workerRepository.findByWorkerIdAndUserId("w1", "u1")).thenReturn(Optional.of(entity));

        service.deleteWorker("u1", "w1");

        verify(workerRepository).deleteByWorkerIdAndUserId("w1", "u1");
        verify(clientFactory).remove("w1");
    }

    @Test
    void deleteWorker_notFound_throws() {
        when(workerRepository.findByWorkerIdAndUserId("w99", "u1")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.deleteWorker("u1", "w99"));
    }

    // ---- 查询 ----

    @Test
    void listWorkers_returnsDTOList() {
        ClaudeWorkerEntity e1 = buildEntity();
        e1.setName("Worker-A");
        ClaudeWorkerEntity e2 = buildEntity();
        e2.setName("Worker-B");
        when(workerRepository.findByUserId("u1")).thenReturn(List.of(e1, e2));

        List<WorkerDTO> result = service.listWorkers("u1");
        assertEquals(2, result.size());
    }

    @Test
    void getWorker_found_returnsDTO() {
        ClaudeWorkerEntity entity = buildEntity();
        when(workerRepository.findByWorkerIdAndUserId("w1", "u1")).thenReturn(Optional.of(entity));

        WorkerDTO dto = service.getWorker("u1", "w1");
        assertNotNull(dto);
        assertEquals("w1", dto.getWorkerId());
    }

    // ---- 解密 ----

    @Test
    void getDecryptedToken_delegatesToEncryptor() {
        ClaudeWorkerEntity entity = buildEntity();
        entity.setAuthToken("enc-token");
        when(credentialEncryptor.decrypt("enc-token")).thenReturn("plain-token");

        assertEquals("plain-token", service.getDecryptedToken(entity));
    }

    @Test
    void getDecryptedSshPassword_null_returnsNull() {
        ClaudeWorkerEntity entity = buildEntity();
        entity.setSshPassword(null);

        assertNull(service.getDecryptedSshPassword(entity));
        verify(credentialEncryptor, never()).decrypt(anyString());
    }

    @Test
    void getDecryptedCodexConfig_null_returnsNull() {
        ClaudeWorkerEntity entity = buildEntity();
        entity.setCodexConfig(null);

        assertNull(service.getDecryptedCodexConfig(entity));
    }

    @Test
    void getDecryptedCodexConfig_decryptsAuthToken() {
        ClaudeWorkerEntity entity = buildEntity();
        entity.setCodexConfig(CodexConfig.builder()
                .baseUrl("http://codex:3032")
                .authToken("enc-codex-tk")
                .model("codex-mini")
                .build());
        when(credentialEncryptor.decrypt("enc-codex-tk")).thenReturn("plain-codex-tk");

        CodexConfig result = service.getDecryptedCodexConfig(entity);
        assertNotNull(result);
        assertEquals("http://codex:3032", result.getBaseUrl());
        assertEquals("plain-codex-tk", result.getAuthToken());
        assertEquals("codex-mini", result.getModel());
    }

    // ---- 状态更新 ----

    @Test
    void updateWorkerStatus_setsStatusAndHostname() {
        ClaudeWorkerEntity entity = buildEntity();
        when(workerRepository.findByWorkerId("w1")).thenReturn(Optional.of(entity));

        service.updateWorkerStatus("w1", "ONLINE", Map.of("hostname", "my-host", "version", "1.2.3"));

        assertEquals("ONLINE", entity.getStatus());
        assertEquals("my-host", entity.getHostname());
        assertEquals("1.2.3", entity.getWorkerVersion());
        assertNotNull(entity.getLastHeartbeat());
        verify(workerRepository).save(entity);
    }

    @Test
    void updateWorkerStatus_nullHealthData_noExceptions() {
        ClaudeWorkerEntity entity = buildEntity();
        when(workerRepository.findByWorkerId("w1")).thenReturn(Optional.of(entity));

        service.updateWorkerStatus("w1", "ONLINE", null);

        assertEquals("ONLINE", entity.getStatus());
        verify(workerRepository).save(entity);
    }

    // ---- DTO 转换 ----

    @Test
    void toDTO_masksSensitiveFields() {
        ClaudeWorkerEntity entity = buildEntity();
        entity.setSshPassword("enc-ssh-pw");
        entity.setCodeServerPassword("enc-cs-pw");
        when(workerRepository.findByWorkerIdAndUserId("w1", "u1")).thenReturn(Optional.of(entity));

        WorkerDTO dto = service.getWorker("u1", "w1");
        assertTrue(dto.isSshPasswordConfigured());
        assertTrue(dto.isCodeServerPasswordConfigured());
    }

    @Test
    void toDTO_noPasswords_flagsFalse() {
        ClaudeWorkerEntity entity = buildEntity();
        entity.setSshPassword(null);
        entity.setCodeServerPassword(null);
        when(workerRepository.findByWorkerIdAndUserId("w1", "u1")).thenReturn(Optional.of(entity));

        WorkerDTO dto = service.getWorker("u1", "w1");
        assertFalse(dto.isSshPasswordConfigured());
        assertFalse(dto.isCodeServerPasswordConfigured());
    }

    // ---- helper ----

    private ClaudeWorkerEntity buildEntity() {
        ClaudeWorkerEntity entity = new ClaudeWorkerEntity();
        entity.setWorkerId("w1");
        entity.setUserId("u1");
        entity.setTenantId("t1");
        entity.setName("Test Worker");
        entity.setBaseUrl("http://original");
        entity.setAuthToken("enc-original");
        entity.setAuthMode("SUBSCRIPTION");
        entity.setStatus("UNKNOWN");
        return entity;
    }
}
