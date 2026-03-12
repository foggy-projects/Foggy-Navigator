package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.SharingKeyDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.common.form.SharingKeyCreateForm;
import com.foggy.navigator.common.form.SharingKeyUpdateForm;
import com.foggy.navigator.session.registry.DefaultA2aAgentRegistry;
import com.foggy.navigator.session.repository.SharingKeyRepository;
import com.foggy.navigator.session.util.SharingKeyGenerator;
import com.foggy.navigator.spi.agent.A2aAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SharingKeyService 单元测试 — L1
 */
@ExtendWith(MockitoExtension.class)
class SharingKeyServiceTest {

    @Mock private SharingKeyRepository repository;
    @Mock private SharingKeyGenerator keyGenerator;
    @Mock private DefaultA2aAgentRegistry agentRegistry;

    @InjectMocks private SharingKeyService service;

    // ---- create ----

    @Test
    void create_generatesKeyAndSaves() {
        SharingKeyCreateForm form = new SharingKeyCreateForm();
        form.setAgentId("agent-1");
        form.setLabel("Test Key");

        A2aAgent agent = mock(A2aAgent.class);
        when(agentRegistry.resolveAgent("agent-1", "u1")).thenReturn(Optional.of(agent));
        when(keyGenerator.generate()).thenReturn("shk-random123");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(keyGenerator.mask(anyString())).thenReturn("shk-***m123");

        SharingKeyDTO dto = service.create("u1", form);

        assertNotNull(dto);
        assertEquals("shk-random123", dto.getSharingKey()); // plain key returned only once
        assertEquals("agent-1", dto.getAgentId());

        ArgumentCaptor<SharingKeyEntity> captor = ArgumentCaptor.forClass(SharingKeyEntity.class);
        verify(repository).save(captor.capture());
        assertEquals("shk-random123", captor.getValue().getSharingKey());
        assertEquals(1, captor.getValue().getMaxTurns()); // default
        assertEquals(50, captor.getValue().getMaxDailyCalls()); // default
        assertTrue(captor.getValue().getEnabled());
    }

    @Test
    void create_blankAgentId_throws() {
        SharingKeyCreateForm form = new SharingKeyCreateForm();
        form.setAgentId("  ");

        assertThrows(IllegalArgumentException.class, () -> service.create("u1", form));
    }

    @Test
    void create_nullAgentId_throws() {
        SharingKeyCreateForm form = new SharingKeyCreateForm();
        form.setAgentId(null);

        assertThrows(IllegalArgumentException.class, () -> service.create("u1", form));
    }

    @Test
    void create_agentNotOwned_throws() {
        SharingKeyCreateForm form = new SharingKeyCreateForm();
        form.setAgentId("agent-not-mine");

        when(agentRegistry.resolveAgent("agent-not-mine", "u1")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.create("u1", form));
    }

    @Test
    void create_customMaxTurns() {
        SharingKeyCreateForm form = new SharingKeyCreateForm();
        form.setAgentId("agent-1");
        form.setMaxTurns(5);
        form.setMaxDailyCalls(100);

        A2aAgent agent = mock(A2aAgent.class);
        when(agentRegistry.resolveAgent("agent-1", "u1")).thenReturn(Optional.of(agent));
        when(keyGenerator.generate()).thenReturn("shk-abc");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(keyGenerator.mask(anyString())).thenReturn("shk-***c");

        service.create("u1", form);

        ArgumentCaptor<SharingKeyEntity> captor = ArgumentCaptor.forClass(SharingKeyEntity.class);
        verify(repository).save(captor.capture());
        assertEquals(5, captor.getValue().getMaxTurns());
        assertEquals(100, captor.getValue().getMaxDailyCalls());
    }

    // ---- listByOwner ----

    @Test
    void listByOwner_returnsDTO_withMaskedKey() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        when(repository.findByOwnerUserIdOrderByCreatedAtDesc("u1"))
                .thenReturn(List.of(entity));
        when(keyGenerator.mask("shk-full-key")).thenReturn("shk-***-key");

        List<SharingKeyDTO> list = service.listByOwner("u1");

        assertEquals(1, list.size());
        assertNull(list.get(0).getSharingKey()); // plain key NOT returned
        assertEquals("shk-***-key", list.get(0).getMaskedKey());
    }

    // ---- update ----

    @Test
    void update_partialFields() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        when(repository.findById("sk-1")).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(keyGenerator.mask(anyString())).thenReturn("shk-***");

        SharingKeyUpdateForm form = new SharingKeyUpdateForm();
        form.setLabel("Updated Label");
        form.setMaxDailyCalls(200);
        // maxTurns is null → should not change

        service.update("sk-1", "u1", form);

        assertEquals("Updated Label", entity.getLabel());
        assertEquals(200, entity.getMaxDailyCalls());
        assertEquals(1, entity.getMaxTurns()); // unchanged
    }

    @Test
    void update_wrongOwner_throws() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "other-user");
        when(repository.findById("sk-1")).thenReturn(Optional.of(entity));

        assertThrows(SecurityException.class,
                () -> service.update("sk-1", "u1", new SharingKeyUpdateForm()));
    }

    @Test
    void update_notFound_throws() {
        when(repository.findById("sk-99")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.update("sk-99", "u1", new SharingKeyUpdateForm()));
    }

    // ---- revoke ----

    @Test
    void revoke_disablesKey() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        when(repository.findById("sk-1")).thenReturn(Optional.of(entity));

        service.revoke("sk-1", "u1");

        assertFalse(entity.getEnabled());
        verify(repository).save(entity);
    }

    @Test
    void revoke_wrongOwner_throws() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "other");
        when(repository.findById("sk-1")).thenReturn(Optional.of(entity));

        assertThrows(SecurityException.class, () -> service.revoke("sk-1", "u1"));
    }

    // ---- delete ----

    @Test
    void delete_removesFromRepo() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        when(repository.findById("sk-1")).thenReturn(Optional.of(entity));

        service.delete("sk-1", "u1");

        verify(repository).delete(entity);
    }

    @Test
    void delete_wrongOwner_throws() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "other");
        when(repository.findById("sk-1")).thenReturn(Optional.of(entity));

        assertThrows(SecurityException.class, () -> service.delete("sk-1", "u1"));
    }

    // ---- validateAndConsume ----

    @Test
    void validateAndConsume_validKey_incrementsAndReturns() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setTodayCalls(3);
        entity.setCallDate(LocalDate.now());
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SharingKeyEntity result = service.validateAndConsume("shk-full-key");

        assertEquals(4, result.getTodayCalls());
        assertNotNull(result.getLastUsedAt());
        verify(repository).save(entity);
    }

    @Test
    void validateAndConsume_invalidKey_throws() {
        when(repository.findBySharingKey("shk-invalid")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.validateAndConsume("shk-invalid"));
    }

    @Test
    void validateAndConsume_disabledKey_throws() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setEnabled(false);
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateAndConsume("shk-full-key"));
        assertTrue(ex.getMessage().contains("disabled"));
    }

    @Test
    void validateAndConsume_expiredKey_throws() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setExpiresAt(LocalDateTime.now().minusDays(1)); // expired yesterday
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateAndConsume("shk-full-key"));
        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    void validateAndConsume_quotaExceeded_throws() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setTodayCalls(50);
        entity.setMaxDailyCalls(50);
        entity.setCallDate(LocalDate.now());
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateAndConsume("shk-full-key"));
        assertTrue(ex.getMessage().contains("limit"));
    }

    @Test
    void validateAndConsume_newDay_resetsCounter() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setTodayCalls(50);
        entity.setMaxDailyCalls(50);
        entity.setCallDate(LocalDate.now().minusDays(1)); // yesterday
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SharingKeyEntity result = service.validateAndConsume("shk-full-key");

        // Counter should be reset to 0 then incremented to 1
        assertEquals(1, result.getTodayCalls());
        assertEquals(LocalDate.now(), result.getCallDate());
    }

    @Test
    void validateAndConsume_nullCallDate_resetsCounter() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setTodayCalls(0);
        entity.setCallDate(null); // never used before
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SharingKeyEntity result = service.validateAndConsume("shk-full-key");

        assertEquals(1, result.getTodayCalls());
        assertEquals(LocalDate.now(), result.getCallDate());
    }

    @Test
    void validateAndConsume_noExpiry_passes() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setExpiresAt(null); // no expiry
        entity.setCallDate(LocalDate.now());
        entity.setTodayCalls(0);
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SharingKeyEntity result = service.validateAndConsume("shk-full-key");

        assertEquals(1, result.getTodayCalls());
    }

    // ---- helper ----

    private SharingKeyEntity buildEntity(String id, String agentId, String ownerUserId) {
        SharingKeyEntity entity = new SharingKeyEntity();
        entity.setId(id);
        entity.setSharingKey("shk-full-key");
        entity.setAgentId(agentId);
        entity.setOwnerUserId(ownerUserId);
        entity.setLabel("Test");
        entity.setMaxTurns(1);
        entity.setMaxDailyCalls(50);
        entity.setTodayCalls(0);
        entity.setEnabled(true);
        return entity;
    }
}
