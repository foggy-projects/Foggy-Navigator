package com.foggy.navigator.auth.init;

import com.foggy.navigator.auth.repository.UserRepository;
import com.foggy.navigator.auth.util.PasswordUtil;
import com.foggy.navigator.common.entity.UserEntity;
import com.foggy.navigator.common.enums.UserRole;
import com.foggy.navigator.common.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SystemInitializer 单元测试 — L1
 */
@ExtendWith(MockitoExtension.class)
class SystemInitializerTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordUtil passwordUtil;

    @InjectMocks private SystemInitializer initializer;

    // ---- 首次初始化：创建 ROOT 用户 ----

    @Test
    void run_createsRootUser_whenNotExists() throws Exception {
        setFields("root", "root123", "root@foggy.local", false);
        when(userRepository.findByUsername("root")).thenReturn(Optional.empty());
        when(passwordUtil.encode("root123")).thenReturn("hashed-root123");

        initializer.run();

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());

        UserEntity saved = captor.getValue();
        assertEquals("root", saved.getUsername());
        assertEquals("hashed-root123", saved.getPasswordHash());
        assertEquals("root@foggy.local", saved.getEmail());
        assertEquals("System Root", saved.getDisplayName());
        assertEquals(UserRole.SUPER_ADMIN.name(), saved.getRoles());
        assertEquals(UserStatus.ACTIVE, saved.getStatus());
        assertNotNull(saved.getId());
    }

    // ---- ROOT 已存在且不重置密码 ----

    @Test
    void run_skips_whenRootExists_andNoPasswordReset() throws Exception {
        setFields("root", "root123", "root@foggy.local", false);
        UserEntity existing = new UserEntity();
        existing.setUsername("root");
        when(userRepository.findByUsername("root")).thenReturn(Optional.of(existing));

        initializer.run();

        verify(userRepository, never()).save(any());
    }

    // ---- ROOT 已存在且需要重置密码 ----

    @Test
    void run_resetsPassword_whenFlagIsTrue() throws Exception {
        setFields("root", "newpass", "root@foggy.local", true);
        UserEntity existing = new UserEntity();
        existing.setUsername("root");
        existing.setPasswordHash("old-hash");
        when(userRepository.findByUsername("root")).thenReturn(Optional.of(existing));
        when(passwordUtil.encode("newpass")).thenReturn("hashed-newpass");

        initializer.run();

        verify(userRepository).save(existing);
        assertEquals("hashed-newpass", existing.getPasswordHash());
    }

    // ---- 自定义用户名和邮箱 ----

    @Test
    void run_usesCustomUsername() throws Exception {
        setFields("admin", "admin123", "admin@company.com", false);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(passwordUtil.encode("admin123")).thenReturn("hashed-admin123");

        initializer.run();

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertEquals("admin", captor.getValue().getUsername());
        assertEquals("admin@company.com", captor.getValue().getEmail());
    }

    // ---- 辅助 ----

    private void setFields(String username, String password, String email, boolean reset) throws Exception {
        setField("rootUsername", username);
        setField("rootPassword", password);
        setField("rootEmail", email);
        setField("passwordReset", reset);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = SystemInitializer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(initializer, value);
    }
}
