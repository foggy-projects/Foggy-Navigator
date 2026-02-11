package com.foggy.navigator.metadata.query.config.service;

import com.foggy.navigator.common.dto.UserMemoryDTO;
import com.foggy.navigator.common.entity.UserMemoryEntity;
import com.foggy.navigator.common.enums.UserMemoryCategory;
import com.foggy.navigator.common.enums.UserMemorySource;
import com.foggy.navigator.common.form.UserMemoryForm;
import com.foggy.navigator.metadata.query.config.repository.UserMemoryRepository;
import com.foggy.navigator.spi.memory.UserMemoryManager;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 用户记忆管理实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMemoryManagerImpl implements UserMemoryManager {

    private static final int MAX_MEMORIES_IN_CONTEXT = 50;
    private static final int MAX_MEMORIES_PER_USER = 200;

    private final UserMemoryRepository userMemoryRepo;

    @Override
    @Transactional
    public String saveMemory(String userId, String tenantId, UserMemoryForm form, UserMemorySource source) {
        log.info("Saving user memory: userId={}, category={}, source={}", userId,
                form.getCategory(), source);

        // 去重：检查是否已有完全相同内容的记忆
        String normalizedContent = form.getContent().trim();
        List<UserMemoryEntity> existing = userMemoryRepo.findByUserIdOrderByUpdatedAtDesc(userId);
        for (UserMemoryEntity e : existing) {
            if (e.getContent().trim().equalsIgnoreCase(normalizedContent)) {
                log.info("Duplicate memory detected, skipping save: userId={}, content={}", userId, normalizedContent);
                return e.getId();
            }
        }

        UserMemoryEntity entity = new UserMemoryEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setCategory(form.getCategory() != null ? form.getCategory() : UserMemoryCategory.FACT);
        entity.setContent(normalizedContent);
        entity.setSource(source);

        userMemoryRepo.save(entity);
        log.info("User memory saved: id={}", entity.getId());

        // 淘汰超出上限的最旧记忆
        evictOldestIfOverLimit(userId);

        return entity.getId();
    }

    private void evictOldestIfOverLimit(String userId) {
        long count = userMemoryRepo.countByUserId(userId);
        if (count > MAX_MEMORIES_PER_USER) {
            int toDelete = (int) (count - MAX_MEMORIES_PER_USER);
            List<UserMemoryEntity> oldest = userMemoryRepo.findByUserIdOrderByUpdatedAtAsc(userId);
            List<UserMemoryEntity> toRemove = oldest.subList(0, Math.min(toDelete, oldest.size()));
            userMemoryRepo.deleteAll(toRemove);
            log.info("Evicted {} oldest memories for userId={}, remaining={}", toRemove.size(), userId, MAX_MEMORIES_PER_USER);
        }
    }

    @Override
    @Transactional
    public void deleteMemory(String id) {
        log.info("Deleting user memory: id={}", id);
        if (!userMemoryRepo.existsById(id)) {
            throw RX.throwB("Memory not found: " + id);
        }
        userMemoryRepo.deleteById(id);
    }

    @Override
    @Transactional
    public void updateMemory(String id, UserMemoryForm form) {
        log.info("Updating user memory: id={}", id);
        UserMemoryEntity entity = userMemoryRepo.findById(id)
                .orElseThrow(() -> RX.throwB("Memory not found: " + id));

        if (form.getCategory() != null) {
            entity.setCategory(form.getCategory());
        }
        if (form.getContent() != null) {
            entity.setContent(form.getContent());
        }

        userMemoryRepo.save(entity);
    }

    @Override
    public List<UserMemoryDTO> listMemories(String userId) {
        return userMemoryRepo.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public String buildMemoryContext(String userId) {
        List<UserMemoryEntity> memories = userMemoryRepo.findByUserIdOrderByUpdatedAtDesc(userId);
        if (memories.isEmpty()) {
            return null;
        }

        // 限制条数
        if (memories.size() > MAX_MEMORIES_IN_CONTEXT) {
            memories = memories.subList(0, MAX_MEMORIES_IN_CONTEXT);
        }

        // 按 category 分组
        Map<UserMemoryCategory, List<UserMemoryEntity>> grouped = memories.stream()
                .collect(Collectors.groupingBy(UserMemoryEntity::getCategory));

        StringBuilder sb = new StringBuilder();
        sb.append("## User Memory\n\n");
        sb.append("以下是关于当前用户的长期记忆，请在回答时参考这些信息：\n\n");

        for (UserMemoryCategory category : UserMemoryCategory.values()) {
            List<UserMemoryEntity> items = grouped.get(category);
            if (items == null || items.isEmpty()) {
                continue;
            }
            sb.append("### ").append(category.getDescription()).append("\n");
            for (UserMemoryEntity item : items) {
                sb.append("- ").append(item.getContent()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().stripTrailing();
    }

    private UserMemoryDTO toDTO(UserMemoryEntity entity) {
        UserMemoryDTO dto = new UserMemoryDTO();
        dto.setId(entity.getId());
        dto.setUserId(entity.getUserId());
        dto.setCategory(entity.getCategory());
        dto.setContent(entity.getContent());
        dto.setSource(entity.getSource());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
