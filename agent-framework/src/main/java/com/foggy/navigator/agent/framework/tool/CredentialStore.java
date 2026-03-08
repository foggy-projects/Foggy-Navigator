package com.foggy.navigator.agent.framework.tool;

import java.util.List;

/**
 * 凭证存储接口
 * 负责用户工具凭证的存储、查询、加密管理
 */
public interface CredentialStore {

    /**
     * 保存用户凭证
     */
    void save(UserToolCredential credential);

    /**
     * 查找用户凭证
     */
    UserToolCredential find(String userId, String toolName);

    /**
     * 查找用户的所有凭证
     */
    List<UserToolCredential> findByUser(String userId);

    /**
     * 删除凭证
     */
    void delete(String userId, String toolName);

    /**
     * 检查凭证是否存在且有效
     */
    boolean isValid(String userId, String toolName);

    /**
     * 刷新凭证（使用refreshToken获取新accessToken）
     */
    UserToolCredential refresh(String userId, String toolName);
}
