package com.foggy.navigator.spi.memory;

import com.foggy.navigator.common.dto.UserMemoryDTO;
import com.foggy.navigator.common.enums.UserMemorySource;
import com.foggy.navigator.common.form.UserMemoryForm;

import java.util.List;

/**
 * 用户记忆管理接口（SPI）
 * 提供用户长期记忆的增删改查能力
 *
 * 设计原则：
 * - 使用 Form/DTO 而非 Entity 作为参数/返回值
 * - buildMemoryContext() 生成可注入 system prompt 的文本
 */
public interface UserMemoryManager {

    /**
     * 保存用户记忆
     * @param userId 用户ID
     * @param tenantId 租户ID
     * @param form 记忆表单
     * @param source 来源（自动/手动）
     * @return 保存后的记忆ID
     */
    String saveMemory(String userId, String tenantId, UserMemoryForm form, UserMemorySource source);

    /**
     * 删除记忆
     * @param id 记忆ID
     */
    void deleteMemory(String id);

    /**
     * 更新记忆
     * @param id 记忆ID
     * @param form 记忆表单（仅更新非 null 字段）
     */
    void updateMemory(String id, UserMemoryForm form);

    /**
     * 获取用户的所有记忆
     * @param userId 用户ID
     * @return 记忆列表（按更新时间倒序）
     */
    List<UserMemoryDTO> listMemories(String userId);

    /**
     * 构建记忆上下文文本（用于注入 system prompt）
     * @param userId 用户ID
     * @return Markdown 格式的记忆文本，无记忆时返回 null
     */
    String buildMemoryContext(String userId);
}
