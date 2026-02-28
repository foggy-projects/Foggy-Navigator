package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.session.sse.UserSseEmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 用户级通知 SSE 端点
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/notifications")
@RequireAuth
@RequiredArgsConstructor
public class NotificationController {

    private final UserSseEmitter userSseEmitter;

    @GetMapping("/stream")
    public SseEmitter stream() {
        CurrentUser user = UserContext.getCurrentUser();
        return userSseEmitter.createEmitter(user.getUserId());
    }
}
