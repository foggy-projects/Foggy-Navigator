package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.SharingKeyDTO;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.common.entity.UserEntity;
import com.foggy.navigator.common.form.SharingKeyCreateForm;
import com.foggy.navigator.common.form.SharingKeyUpdateForm;
import com.foggy.navigator.auth.repository.UserRepository;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.SharingKeyRepository;
import com.foggy.navigator.session.util.SharingKeyGenerator;
import com.foggy.navigator.spi.agent.A2aAgent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
    @Mock private UnifiedAgentResolver agentResolver;
    @Mock private UserRepository userRepository;

    @InjectMocks private SharingKeyService service;

    // ---- create ----

    @Test
    void create_generatesKeyAndSaves() {
        SharingKeyCreateForm form = new SharingKeyCreateForm();
        form.setAgentId("agent-1");
        form.setLabel("Test Key");

        A2aAgent agent = mock(A2aAgent.class);
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
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

        when(agentResolver.resolveAgent(eq("agent-not-mine"), any())).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.create("u1", form));
    }

    @Test
    void create_customMaxTurns() {
        SharingKeyCreateForm form = new SharingKeyCreateForm();
        form.setAgentId("agent-1");
        form.setMaxTurns(5);
        form.setMaxDailyCalls(100);

        A2aAgent agent = mock(A2aAgent.class);
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
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

    @Test
    void validateOperationAndConsume_allowedOperationConsumesOnce() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setAllowedOperations("ask,task:get");
        entity.setCallDate(LocalDate.now());
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));

        SharingKeyEntity result = service.validateOperationAndConsume("shk-full-key", "ask");

        assertEquals(1, result.getTodayCalls());
        verify(repository).save(entity);
    }

    @Test
    void validateOperationAndConsume_disallowedOperationDoesNotConsume() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setAllowedOperations("task:get");
        entity.setCallDate(LocalDate.now());
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));

        assertThrows(IllegalArgumentException.class,
                () -> service.validateOperationAndConsume("shk-full-key", "ask"));

        assertEquals(0, entity.getTodayCalls());
        verify(repository, never()).save(any());
    }

    @Test
    void validateForKeyOnly_quotaExceeded_doesNotConsumeAndStillPasses() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setTodayCalls(50);
        entity.setMaxDailyCalls(50);
        entity.setCallDate(LocalDate.now());
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));

        SharingKeyEntity result = service.validateForKeyOnly("shk-full-key");

        assertSame(entity, result);
        assertEquals(50, result.getTodayCalls());
        verify(repository, never()).save(any());
    }

    @Test
    void validateForKeyOnly_disabledKey_throws() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setEnabled(false);
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateForKeyOnly("shk-full-key"));
        assertTrue(ex.getMessage().contains("disabled"));
        verify(repository, never()).save(any());
    }

    @Test
    void findByIdForUpdateDeclaresExactPessimisticPrimaryKeyLock() throws Exception {
        Method method = SharingKeyRepository.class.getMethod(
                "findByIdForUpdate", String.class);

        Lock lock = method.getAnnotation(Lock.class);
        Query query = method.getAnnotation(Query.class);

        assertNotNull(lock);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
        assertNotNull(query);
        String normalized = query.value().replaceAll("\\s+", " ").trim();
        assertEquals(
                "select sharingKey from SharingKeyEntity sharingKey where sharingKey.id = :id",
                normalized);
        Param parameter = method.getParameters()[0].getAnnotation(Param.class);
        assertNotNull(parameter);
        assertEquals("id", parameter.value());
    }

    @Test
    void mintAskAuthorityIsReadOnlyRawKeyFreeAndHasRedactedRepresentation() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setAllowedOperations(" ");
        entity.setMaxTurns(3);
        entity.setSystemPrompt("private default prompt");
        entity.setMaxDailyCalls(50);
        entity.setTodayCalls(50);
        entity.setCallDate(LocalDate.now());
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));
        when(userRepository.findById("u1"))
                .thenReturn(Optional.of(owner("u1", "tenant-1")));

        SharingKeyService.SharedAskAuthority authority =
                service.mintAskAuthority("shk-full-key");

        assertEquals("sk-1", authority.sharingKeyId());
        assertEquals("u1", authority.ownerUserId());
        assertEquals("tenant-1", authority.tenantId());
        assertEquals("agent-1", authority.agentId());
        assertEquals(3, authority.preflightPolicy().maxTurns());
        assertEquals("private default prompt",
                authority.preflightPolicy().systemPrompt());
        assertEquals("SharedAskAuthority[content-free]", authority.toString());
        assertEquals("SharedAskPolicySnapshot[content-redacted]",
                authority.preflightPolicy().toString());
        assertFalse(authority.toString().contains("shk-full-key"));
        assertFalse(authority.preflightPolicy().toString()
                .contains("private default prompt"));
        for (Constructor<?> constructor
                : SharingKeyService.SharedAskAuthority.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        for (Field field : SharingKeyService.SharedAskAuthority.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertTrue(Modifier.isFinal(field.getModifiers()));
        }
        verify(repository, never()).findByIdForUpdate(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void mintAskAuthorityRejectsOperationOrOwnerTenantWithoutMutation() {
        SharingKeyEntity entity = buildEntity("sk-1", "agent-1", "u1");
        entity.setAllowedOperations("task:get");
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));

        assertThrows(IllegalArgumentException.class,
                () -> service.mintAskAuthority("shk-full-key"));
        verifyNoInteractions(userRepository);
        verify(repository, never()).save(any());

        reset(repository, userRepository);
        entity.setAllowedOperations("ask");
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));
        when(userRepository.findById("u1")).thenReturn(Optional.empty());
        SecurityException missingOwner = assertThrows(SecurityException.class,
                () -> service.mintAskAuthority("shk-full-key"));
        assertEquals("shared resource is not accessible", missingOwner.getMessage());
        verify(repository, never()).save(any());

        reset(repository, userRepository);
        when(repository.findBySharingKey("shk-full-key")).thenReturn(Optional.of(entity));
        when(userRepository.findById("u1"))
                .thenReturn(Optional.of(owner("u1", " ")));
        SecurityException blankTenant = assertThrows(SecurityException.class,
                () -> service.mintAskAuthority("shk-full-key"));
        assertEquals("shared resource is not accessible", blankTenant.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void consumeAuthorizedAskLocksOnceUsesLatestPolicyAndRejectsSerializedSecondSlot() {
        SharingKeyEntity preflight = buildEntity("sk-1", "agent-1", "u1");
        preflight.setMaxTurns(1);
        preflight.setSystemPrompt("old prompt");
        when(repository.findBySharingKey("shk-full-key"))
                .thenReturn(Optional.of(preflight));
        when(userRepository.findById("u1"))
                .thenReturn(Optional.of(owner("u1", "tenant-1")));
        SharingKeyService.SharedAskAuthority authority =
                service.mintAskAuthority("shk-full-key");

        clearInvocations(repository, userRepository);
        SharingKeyEntity locked = buildEntity("sk-1", "agent-1", "u1");
        locked.setAllowedOperations("ask,task:get");
        locked.setMaxTurns(5);
        locked.setSystemPrompt("latest prompt");
        locked.setMaxDailyCalls(2);
        locked.setTodayCalls(1);
        locked.setCallDate(LocalDate.now());
        when(repository.findByIdForUpdate("sk-1"))
                .thenReturn(Optional.of(locked));
        when(userRepository.findById("u1"))
                .thenReturn(Optional.of(owner("u1", "tenant-1")));

        SharingKeyService.SharedAskPolicySnapshot policy =
                service.consumeAuthorizedAsk(authority);
        LocalDateTime firstLastUsedAt = locked.getLastUsedAt();

        assertEquals(2, locked.getTodayCalls());
        assertNotNull(firstLastUsedAt);
        assertEquals(5, policy.maxTurns());
        assertEquals("latest prompt", policy.systemPrompt());
        IllegalArgumentException exhausted = assertThrows(IllegalArgumentException.class,
                () -> service.consumeAuthorizedAsk(authority));
        assertTrue(exhausted.getMessage().contains("Daily call limit exceeded"));
        assertEquals(2, locked.getTodayCalls());
        assertSame(firstLastUsedAt, locked.getLastUsedAt());
        verify(repository, times(2)).findByIdForUpdate("sk-1");
        verify(repository, times(1)).save(locked);
        verify(repository, never()).findBySharingKey(anyString());
    }

    @Test
    void consumeAuthorizedAskRejectsIdentityTenantAndCurrentAuthorityDriftWithoutSave() {
        SharingKeyEntity preflight = buildEntity("sk-1", "agent-1", "u1");
        preflight.setAllowedOperations("ask");
        when(repository.findBySharingKey("shk-full-key"))
                .thenReturn(Optional.of(preflight));
        when(userRepository.findById("u1"))
                .thenReturn(Optional.of(owner("u1", "tenant-1")));
        SharingKeyService.SharedAskAuthority authority =
                service.mintAskAuthority("shk-full-key");

        SharingKeyRepository foreignRepository = mock(SharingKeyRepository.class);
        UserRepository foreignUserRepository = mock(UserRepository.class);
        SharingKeyService foreignService = new SharingKeyService(
                foreignRepository,
                mock(SharingKeyGenerator.class),
                mock(UnifiedAgentResolver.class),
                foreignUserRepository,
                "http://localhost:8112");
        SecurityException foreignIssuer = assertThrows(SecurityException.class,
                () -> foreignService.consumeAuthorizedAsk(authority));
        assertEquals("shared resource is not accessible", foreignIssuer.getMessage());
        assertThrows(SecurityException.class,
                () -> service.consumeAuthorizedAsk(null));
        verifyNoInteractions(foreignRepository, foreignUserRepository);

        reset(repository, userRepository);
        when(repository.findByIdForUpdate("sk-1")).thenReturn(Optional.empty());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.consumeAuthorizedAsk(authority));
        assertEquals("Invalid sharing key", missing.getMessage());
        verifyNoInteractions(userRepository);
        verify(repository, never()).save(any());

        reset(repository, userRepository);
        SharingKeyEntity rowIdDrift = buildEntity("sk-2", "agent-1", "u1");
        when(repository.findByIdForUpdate("sk-1"))
                .thenReturn(Optional.of(rowIdDrift));
        assertThrows(SecurityException.class,
                () -> service.consumeAuthorizedAsk(authority));
        verifyNoInteractions(userRepository);
        verify(repository, never()).save(any());

        reset(repository, userRepository);
        SharingKeyEntity agentDrift = buildEntity("sk-1", "agent-2", "u1");
        when(repository.findByIdForUpdate("sk-1"))
                .thenReturn(Optional.of(agentDrift));
        assertThrows(SecurityException.class,
                () -> service.consumeAuthorizedAsk(authority));
        verifyNoInteractions(userRepository);
        verify(repository, never()).save(any());

        reset(repository, userRepository);
        SharingKeyEntity ownerDrift = buildEntity("sk-1", "agent-1", "u2");
        when(repository.findByIdForUpdate("sk-1"))
                .thenReturn(Optional.of(ownerDrift));
        assertThrows(SecurityException.class,
                () -> service.consumeAuthorizedAsk(authority));
        verify(repository, never()).save(any());

        reset(repository, userRepository);
        SharingKeyEntity tenantDrift = buildEntity("sk-1", "agent-1", "u1");
        tenantDrift.setAllowedOperations("ask");
        when(repository.findByIdForUpdate("sk-1"))
                .thenReturn(Optional.of(tenantDrift));
        when(userRepository.findById("u1"))
                .thenReturn(Optional.of(owner("u1", "tenant-2")));
        assertThrows(SecurityException.class,
                () -> service.consumeAuthorizedAsk(authority));
        verify(repository, never()).save(any());

        reset(repository, userRepository);
        SharingKeyEntity operationDrift = buildEntity("sk-1", "agent-1", "u1");
        operationDrift.setAllowedOperations("task:get");
        when(repository.findByIdForUpdate("sk-1"))
                .thenReturn(Optional.of(operationDrift));
        assertThrows(IllegalArgumentException.class,
                () -> service.consumeAuthorizedAsk(authority));
        verifyNoInteractions(userRepository);
        verify(repository, never()).save(any());
        verify(repository, never()).findBySharingKey(anyString());
    }

    @Test
    void consumeAuthorizedAskRechecksLockedUsabilityAndPreservesDayRollover() {
        SharingKeyEntity preflight = buildEntity("sk-1", "agent-1", "u1");
        preflight.setAllowedOperations("ask");
        when(repository.findBySharingKey("shk-full-key"))
                .thenReturn(Optional.of(preflight));
        when(userRepository.findById("u1"))
                .thenReturn(Optional.of(owner("u1", "tenant-1")));
        SharingKeyService.SharedAskAuthority authority =
                service.mintAskAuthority("shk-full-key");

        reset(repository, userRepository);
        SharingKeyEntity disabled = buildEntity("sk-1", "agent-1", "u1");
        disabled.setEnabled(false);
        when(repository.findByIdForUpdate("sk-1"))
                .thenReturn(Optional.of(disabled));
        IllegalArgumentException disabledFailure = assertThrows(
                IllegalArgumentException.class,
                () -> service.consumeAuthorizedAsk(authority));
        assertTrue(disabledFailure.getMessage().contains("disabled"));
        verifyNoInteractions(userRepository);
        verify(repository, never()).save(any());

        reset(repository, userRepository);
        SharingKeyEntity expired = buildEntity("sk-1", "agent-1", "u1");
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(repository.findByIdForUpdate("sk-1"))
                .thenReturn(Optional.of(expired));
        IllegalArgumentException expiredFailure = assertThrows(
                IllegalArgumentException.class,
                () -> service.consumeAuthorizedAsk(authority));
        assertTrue(expiredFailure.getMessage().contains("expired"));
        verifyNoInteractions(userRepository);
        verify(repository, never()).save(any());

        reset(repository, userRepository);
        SharingKeyEntity newDay = buildEntity("sk-1", "agent-1", "u1");
        newDay.setAllowedOperations("ask");
        newDay.setMaxDailyCalls(50);
        newDay.setTodayCalls(50);
        newDay.setCallDate(LocalDate.now().minusDays(1));
        when(repository.findByIdForUpdate("sk-1"))
                .thenReturn(Optional.of(newDay));
        when(userRepository.findById("u1"))
                .thenReturn(Optional.of(owner("u1", "tenant-1")));

        service.consumeAuthorizedAsk(authority);

        assertEquals(1, newDay.getTodayCalls());
        assertEquals(LocalDate.now(), newDay.getCallDate());
        verify(repository).save(newDay);
        verify(repository, never()).findBySharingKey(anyString());
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

    private UserEntity owner(String userId, String tenantId) {
        UserEntity owner = new UserEntity();
        owner.setId(userId);
        owner.setTenantId(tenantId);
        return owner;
    }
}
