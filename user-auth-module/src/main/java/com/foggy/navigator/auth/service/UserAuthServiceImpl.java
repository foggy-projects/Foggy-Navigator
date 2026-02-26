package com.foggy.navigator.auth.service;

import com.foggy.navigator.auth.repository.ApiKeyRepository;
import com.foggy.navigator.auth.repository.UserRepository;
import com.foggy.navigator.auth.util.ApiKeyGenerator;
import com.foggy.navigator.auth.util.JwtUtil;
import com.foggy.navigator.auth.util.PasswordUtil;
import com.foggy.navigator.common.dto.ApiKeyDTO;
import com.foggy.navigator.common.dto.LoginResultDTO;
import com.foggy.navigator.common.dto.UserDTO;
import com.foggy.navigator.common.entity.ApiKeyEntity;
import com.foggy.navigator.common.entity.UserEntity;
import com.foggy.navigator.common.enums.UserRole;
import com.foggy.navigator.common.enums.UserStatus;
import com.foggy.navigator.common.form.*;
import com.foggy.navigator.spi.auth.UserAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 用户认证服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAuthServiceImpl implements UserAuthService {

    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PasswordUtil passwordUtil;
    private final JwtUtil jwtUtil;
    private final ApiKeyGenerator apiKeyGenerator;

    @Override
    @Transactional
    public String registerUser(UserRegisterForm form) {
        // 检查用户名是否已存在
        if (userRepository.existsByUsername(form.getUsername())) {
            throw new IllegalArgumentException("用户名已存在");
        }

        // 检查邮箱是否已存在
        if (form.getEmail() != null && userRepository.existsByEmail(form.getEmail())) {
            throw new IllegalArgumentException("邮箱已存在");
        }

        // 创建用户实体
        UserEntity entity = new UserEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setTenantId(form.getTenantId());
        entity.setUsername(form.getUsername());
        entity.setPasswordHash(passwordUtil.encode(form.getPassword()));
        entity.setEmail(form.getEmail());
        entity.setDisplayName(form.getDisplayName());
        entity.setRoles(form.getRoles() != null ? form.getRoles() : UserRole.VIEWER.name());
        entity.setStatus(UserStatus.ACTIVE);

        userRepository.save(entity);
        log.info("User registered: userId={}, username={}", entity.getId(), entity.getUsername());

        return entity.getId();
    }

    @Override
    @Transactional
    public LoginResultDTO login(UserLoginForm form) {
        // 查找用户
        UserEntity entity = userRepository.findByUsername(form.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        // 验证密码
        if (!passwordUtil.matches(form.getPassword(), entity.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 检查用户状态
        if (entity.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalArgumentException("用户已被禁用");
        }

        // 更新最后登录时间
        entity.setLastLoginAt(LocalDateTime.now());
        userRepository.save(entity);

        // 生成Token
        String token = jwtUtil.generateToken(
                entity.getId(),
                entity.getUsername(),
                entity.getTenantId(),
                entity.getRoles()
        );

        // 构造返回结果
        UserDTO userDTO = entityToDTO(entity);

        return LoginResultDTO.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(86400L) // 24小时
                .user(userDTO)
                .build();
    }

    @Override
    public Optional<UserDTO> getUserByToken(String token) {
        try {
            if (!jwtUtil.validateToken(token)) {
                return Optional.empty();
            }

            String userId = jwtUtil.getUserIdFromToken(token);
            return getUser(userId);
        } catch (Exception e) {
            log.warn("Invalid token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<UserDTO> getUserByApiKey(String apiKey) {
        return apiKeyRepository.findByApiKey(apiKey)
                .filter(ak -> ak.getEnabled() && !isApiKeyExpired(ak))
                .flatMap(ak -> {
                    // 更新最后使用时间
                    ak.setLastUsedAt(LocalDateTime.now());
                    apiKeyRepository.save(ak);
                    return getUser(ak.getUserId());
                });
    }

    @Override
    public Optional<UserDTO> getUser(String userId) {
        return userRepository.findById(userId)
                .map(this::entityToDTO);
    }

    @Override
    @Transactional
    public void updateUser(String userId, UserUpdateForm form) {
        UserEntity entity = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (form.getEmail() != null) {
            entity.setEmail(form.getEmail());
        }
        if (form.getDisplayName() != null) {
            entity.setDisplayName(form.getDisplayName());
        }
        if (form.getRoles() != null) {
            entity.setRoles(form.getRoles());
        }
        if (form.getStatus() != null) {
            entity.setStatus(form.getStatus());
        }
        if (form.getNewPassword() != null) {
            entity.setPasswordHash(passwordUtil.encode(form.getNewPassword()));
        }

        userRepository.save(entity);
        log.info("User updated: userId={}", userId);
    }

    @Override
    @Transactional
    public void deleteUser(String userId) {
        UserEntity entity = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        entity.setStatus(UserStatus.DELETED);
        userRepository.save(entity);
        log.info("User deleted: userId={}", userId);
    }

    @Override
    public List<UserDTO> listUsersByTenant(String tenantId) {
        return userRepository.findByTenantIdAndStatus(tenantId, UserStatus.ACTIVE)
                .stream()
                .map(this::entityToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ApiKeyDTO createApiKey(String userId, ApiKeyCreateForm form) {
        // 验证用户存在
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("用户不存在");
        }

        // 生成API Key
        String apiKey = apiKeyGenerator.generate();

        // 创建实体
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity.setApiKey(apiKey);
        entity.setName(form.getName());
        entity.setEnabled(true);
        entity.setExpiresAt(form.getExpiresAt());

        apiKeyRepository.save(entity);
        log.info("API Key created: userId={}, keyId={}", userId, entity.getId());

        // 构造返回DTO（包含明文Key，仅此一次）
        ApiKeyDTO dto = new ApiKeyDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setApiKey(apiKey);
        dto.setMaskedApiKey(apiKeyGenerator.mask(apiKey));

        return dto;
    }

    @Override
    @Transactional
    public void revokeApiKey(String apiKeyId) {
        ApiKeyEntity entity = apiKeyRepository.findById(apiKeyId)
                .orElseThrow(() -> new IllegalArgumentException("API Key不存在"));

        entity.setEnabled(false);
        apiKeyRepository.save(entity);
        log.info("API Key revoked: keyId={}", apiKeyId);
    }

    @Override
    public List<ApiKeyDTO> listApiKeysByUser(String userId) {
        return apiKeyRepository.findByUserId(userId)
                .stream()
                .map(entity -> {
                    ApiKeyDTO dto = new ApiKeyDTO();
                    BeanUtils.copyProperties(entity, dto);
                    dto.setApiKey(null); // 不返回明文
                    dto.setMaskedApiKey(apiKeyGenerator.mask(entity.getApiKey()));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Override
    public String generateServiceToken(String userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        return jwtUtil.generateToken(user.getId(), user.getUsername(), user.getTenantId(), user.getRoles());
    }

    @Override
    public boolean hasRole(String userId, String role) {
        return userRepository.findById(userId)
                .map(user -> user.getRoles() != null && user.getRoles().contains(role))
                .orElse(false);
    }

    @Override
    public boolean belongsToTenant(String userId, String tenantId) {
        return userRepository.findById(userId)
                .map(user -> tenantId.equals(user.getTenantId()))
                .orElse(false);
    }

    /**
     * 实体转DTO
     */
    private UserDTO entityToDTO(UserEntity entity) {
        UserDTO dto = new UserDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }

    /**
     * 检查API Key是否过期
     */
    private boolean isApiKeyExpired(ApiKeyEntity apiKey) {
        return apiKey.getExpiresAt() != null && apiKey.getExpiresAt().isBefore(LocalDateTime.now());
    }
}
