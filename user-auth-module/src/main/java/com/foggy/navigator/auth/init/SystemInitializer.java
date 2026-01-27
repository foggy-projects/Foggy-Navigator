package com.foggy.navigator.auth.init;

import com.foggy.navigator.auth.repository.UserRepository;
import com.foggy.navigator.auth.util.PasswordUtil;
import com.foggy.navigator.common.entity.UserEntity;
import com.foggy.navigator.common.enums.UserRole;
import com.foggy.navigator.common.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 系统初始化器 - 创建 ROOT 账号
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordUtil passwordUtil;

    @Value("${system.root.username:root}")
    private String rootUsername;

    @Value("${system.root.password:root123}")
    private String rootPassword;

    @Value("${system.root.email:root@foggy.local}")
    private String rootEmail;

    @Override
    public void run(String... args) {
        initRootUser();
    }

    private void initRootUser() {
        if (userRepository.existsByUsername(rootUsername)) {
            log.info("ROOT user already exists: {}", rootUsername);
            return;
        }

        UserEntity root = new UserEntity();
        root.setId(UUID.randomUUID().toString());
        root.setTenantId(null); // ROOT 用户不属于任何租户，可访问所有
        root.setUsername(rootUsername);
        root.setPasswordHash(passwordUtil.encode(rootPassword));
        root.setEmail(rootEmail);
        root.setDisplayName("System Root");
        root.setRoles(UserRole.SUPER_ADMIN.name());
        root.setStatus(UserStatus.ACTIVE);

        userRepository.save(root);
        log.info("========================================");
        log.info("ROOT user created successfully!");
        log.info("Username: {}", rootUsername);
        log.info("Password: {}", rootPassword);
        log.info("========================================");
    }
}
