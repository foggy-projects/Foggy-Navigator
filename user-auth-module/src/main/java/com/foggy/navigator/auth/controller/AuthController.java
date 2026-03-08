package com.foggy.navigator.auth.controller;

import com.foggy.navigator.common.dto.LoginResultDTO;
import com.foggy.navigator.common.dto.UserDTO;
import com.foggy.navigator.common.form.UserLoginForm;
import com.foggy.navigator.common.form.UserRegisterForm;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证Controller
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserAuthService userAuthService;

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public RX<String> register(@RequestBody UserRegisterForm form) {
        String userId = userAuthService.registerUser(form);
        return RX.ok(userId);
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public RX<LoginResultDTO> login(@RequestBody UserLoginForm form) {
        LoginResultDTO result = userAuthService.login(form);
        return RX.ok(result);
    }

    /**
     * 获取当前用户信息（通过Token）
     */
    @GetMapping("/me")
    public RX<UserDTO> getCurrentUser(@RequestHeader("Authorization") String authorization) {
        String token = extractToken(authorization);
        return userAuthService.getUserByToken(token)
                .map(RX::ok)
                .orElse(RX.failA("无效的Token"));
    }

    /**
     * 从Authorization头中提取Token
     */
    private String extractToken(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        throw new IllegalArgumentException("无效的Authorization头");
    }
}
