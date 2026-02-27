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

    /** 为 true 时强制重置 ROOT 密码，完成后请从 .env 中删除该行 */
    @Value("${system.root.password-reset:false}")
    private boolean passwordReset;

    @Override
    public void run(String... args) {
        initRootUser();
    }

    private void initRootUser() {
        UserEntity existing = userRepository.findByUsername(rootUsername).orElse(null);

        if (existing == null) {
            // 首次初始化
            UserEntity root = new UserEntity();
            root.setId(UUID.randomUUID().toString());
            root.setTenantId(null);
            root.setUsername(rootUsername);
            root.setPasswordHash(passwordUtil.encode(rootPassword));
            root.setEmail(rootEmail);
            root.setDisplayName("System Root");
            root.setRoles(UserRole.SUPER_ADMIN.name());
            root.setStatus(UserStatus.ACTIVE);
            userRepository.save(root);
            log.info("========================================");
            log.info("ROOT user created: {}", rootUsername);
            log.info("========================================");
            return;
        }

        if (passwordReset) {
            existing.setPasswordHash(passwordUtil.encode(rootPassword));
            userRepository.save(existing);
            log.info("========================================");
            log.info("ROOT password reset for: {}", rootUsername);
            log.warn("请从 .env 中删除 ROOT_PASSWORD_RESET=true 以防止下次启动再次重置");
            log.info("========================================");
        } else {
            log.info("ROOT user already exists: {}", rootUsername);
        }
    }
}
