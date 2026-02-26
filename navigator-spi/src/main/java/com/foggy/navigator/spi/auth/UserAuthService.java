package com.foggy.navigator.spi.auth;

import com.foggy.navigator.common.dto.ApiKeyDTO;
import com.foggy.navigator.common.dto.LoginResultDTO;
import com.foggy.navigator.common.dto.UserDTO;
import com.foggy.navigator.common.form.*;

import java.util.List;
import java.util.Optional;

/**
 * 用户认证服务接口
 */
public interface UserAuthService {

    /**
     * 用户注册
     */
    String registerUser(UserRegisterForm form);

    /**
     * 用户登录
     */
    LoginResultDTO login(UserLoginForm form);

    /**
     * 根据Token获取用户信息
     */
    Optional<UserDTO> getUserByToken(String token);

    /**
     * 根据API Key获取用户信息
     */
    Optional<UserDTO> getUserByApiKey(String apiKey);

    /**
     * 获取用户信息
     */
    Optional<UserDTO> getUser(String userId);

    /**
     * 更新用户信息
     */
    void updateUser(String userId, UserUpdateForm form);

    /**
     * 删除用户
     */
    void deleteUser(String userId);

    /**
     * 查询租户下的用户列表
     */
    List<UserDTO> listUsersByTenant(String tenantId);

    /**
     * 创建API Key
     */
    ApiKeyDTO createApiKey(String userId, ApiKeyCreateForm form);

    /**
     * 撤销API Key
     */
    void revokeApiKey(String apiKeyId);

    /**
     * 查询用户的API Key列表
     */
    List<ApiKeyDTO> listApiKeysByUser(String userId);

    /**
     * 获取用户的第一个有效 API Key（明文），用于注入 CLI 环境变量
     */
    Optional<String> getActiveApiKey(String userId);

    /**
     * 验证用户是否有指定角色
     */
    boolean hasRole(String userId, String role);

    /**
     * 验证用户是否属于指定租户
     */
    boolean belongsToTenant(String userId, String tenantId);
}
