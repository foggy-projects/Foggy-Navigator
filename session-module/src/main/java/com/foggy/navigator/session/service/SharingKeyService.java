package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.SharingKeyDTO;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.common.form.SharingKeyCreateForm;
import com.foggy.navigator.common.form.SharingKeyUpdateForm;
import com.foggy.navigator.session.registry.DefaultA2aAgentRegistry;
import com.foggy.navigator.session.repository.SharingKeyRepository;
import com.foggy.navigator.session.util.SharingKeyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 共享密钥服务 — 管理 Agent 共享密钥的创建、验证、限额
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharingKeyService {

    private final SharingKeyRepository repository;
    private final SharingKeyGenerator keyGenerator;
    private final DefaultA2aAgentRegistry agentRegistry;

    // ==================== Owner 管理 ====================

    /**
     * 创建共享密钥（明文 key 仅此一次返回）
     */
    public SharingKeyDTO create(String ownerUserId, SharingKeyCreateForm form) {
        if (form.getAgentId() == null || form.getAgentId().isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }

        // 校验 Agent 归属于当前用户
        agentRegistry.resolveAgent(form.getAgentId(), ownerUserId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent not found or not owned by you: " + form.getAgentId()));

        String plainKey = keyGenerator.generate();

        SharingKeyEntity entity = new SharingKeyEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setSharingKey(plainKey);
        entity.setAgentId(form.getAgentId());
        entity.setOwnerUserId(ownerUserId);
        entity.setLabel(form.getLabel());
        entity.setSystemPrompt(form.getSystemPrompt());
        entity.setMaxTurns(form.getMaxTurns() != null ? form.getMaxTurns() : 1);
        entity.setMaxDailyCalls(form.getMaxDailyCalls() != null ? form.getMaxDailyCalls() : 50);
        entity.setExpiresAt(form.getExpiresAt());
        entity.setEnabled(true);
        entity.setTodayCalls(0);

        repository.save(entity);
        log.info("Sharing key created: keyId={}, agentId={}, owner={}", entity.getId(), form.getAgentId(), ownerUserId);

        // 返回 DTO（含明文 key，仅此一次）
        SharingKeyDTO dto = toDTO(entity);
        dto.setSharingKey(plainKey);
        return dto;
    }

    /**
     * 列出当前用户的所有共享密钥（maskedKey，不返回明文）
     */
    public List<SharingKeyDTO> listByOwner(String ownerUserId) {
        return repository.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * 更新共享密钥配置
     */
    public SharingKeyDTO update(String id, String ownerUserId, SharingKeyUpdateForm form) {
        SharingKeyEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sharing key not found: " + id));
        if (!entity.getOwnerUserId().equals(ownerUserId)) {
            throw new SecurityException("Not authorized to update this sharing key");
        }

        if (form.getLabel() != null) entity.setLabel(form.getLabel());
        if (form.getSystemPrompt() != null) entity.setSystemPrompt(form.getSystemPrompt());
        if (form.getMaxTurns() != null) entity.setMaxTurns(form.getMaxTurns());
        if (form.getMaxDailyCalls() != null) entity.setMaxDailyCalls(form.getMaxDailyCalls());
        if (form.getExpiresAt() != null) entity.setExpiresAt(form.getExpiresAt());
        if (form.getEnabled() != null) entity.setEnabled(form.getEnabled());

        repository.save(entity);
        log.info("Sharing key updated: keyId={}", id);
        return toDTO(entity);
    }

    /**
     * 停用共享密钥
     */
    public void revoke(String id, String ownerUserId) {
        SharingKeyEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sharing key not found: " + id));
        if (!entity.getOwnerUserId().equals(ownerUserId)) {
            throw new SecurityException("Not authorized to revoke this sharing key");
        }
        entity.setEnabled(false);
        repository.save(entity);
        log.info("Sharing key revoked: keyId={}", id);
    }

    /**
     * 删除共享密钥
     */
    public void delete(String id, String ownerUserId) {
        SharingKeyEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sharing key not found: " + id));
        if (!entity.getOwnerUserId().equals(ownerUserId)) {
            throw new SecurityException("Not authorized to delete this sharing key");
        }
        repository.delete(entity);
        log.info("Sharing key deleted: keyId={}", id);
    }

    // ==================== 外部调用验证 ====================

    /**
     * 验证共享密钥并消费一次调用额度
     *
     * @param sharingKey 外部用户传入的密钥
     * @return 验证通过的 SharingKeyEntity
     * @throws IllegalArgumentException 密钥无效、已停用、已过期、超出限额
     */
    @Transactional
    public SharingKeyEntity validateAndConsume(String sharingKey) {
        SharingKeyEntity entity = repository.findBySharingKey(sharingKey)
                .orElseThrow(() -> new IllegalArgumentException("Invalid sharing key"));

        if (!entity.getEnabled()) {
            throw new IllegalArgumentException("Sharing key is disabled");
        }

        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Sharing key has expired");
        }

        // 每日限额检查（日期变更时重置计数）
        LocalDate today = LocalDate.now();
        if (entity.getCallDate() == null || !entity.getCallDate().equals(today)) {
            entity.setTodayCalls(0);
            entity.setCallDate(today);
        }

        if (entity.getTodayCalls() >= entity.getMaxDailyCalls()) {
            throw new IllegalArgumentException("Daily call limit exceeded (max: " + entity.getMaxDailyCalls() + ")");
        }

        // 消费一次额度
        entity.setTodayCalls(entity.getTodayCalls() + 1);
        entity.setLastUsedAt(LocalDateTime.now());
        repository.save(entity);

        return entity;
    }

    // ==================== 内部方法 ====================

    private SharingKeyDTO toDTO(SharingKeyEntity entity) {
        SharingKeyDTO dto = new SharingKeyDTO();
        dto.setId(entity.getId());
        dto.setAgentId(entity.getAgentId());
        dto.setOwnerUserId(entity.getOwnerUserId());
        dto.setLabel(entity.getLabel());
        dto.setSystemPrompt(entity.getSystemPrompt());
        dto.setMaxTurns(entity.getMaxTurns());
        dto.setMaxDailyCalls(entity.getMaxDailyCalls());
        dto.setTodayCalls(entity.getTodayCalls());
        dto.setExpiresAt(entity.getExpiresAt());
        dto.setEnabled(entity.getEnabled());
        dto.setLastUsedAt(entity.getLastUsedAt());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setSharingKey(null);  // 不返回明文
        dto.setMaskedKey(keyGenerator.mask(entity.getSharingKey()));

        // agentName 冗余展示：尝试从 registry 获取
        try {
            agentRegistry.resolveAgent(entity.getAgentId(), entity.getOwnerUserId())
                    .ifPresent(agent -> dto.setAgentName(agent.getAgentCard().getName()));
        } catch (Exception e) {
            // ignore
        }

        return dto;
    }
}
