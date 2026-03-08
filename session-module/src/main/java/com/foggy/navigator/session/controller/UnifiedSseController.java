package com.foggy.navigator.session.controller;

import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.session.sse.UnifiedSseEmitter;
import com.foggyframework.core.ex.RX;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;

/**
 * Unified SSE Controller — 单连接替代多连接
 *
 * GET  /api/v1/sse/unified     — 建立唯一 SSE 连接
 * POST /api/v1/sse/subscribe   — 订阅 session 事件（校验归属）
 * POST /api/v1/sse/unsubscribe — 取消订阅
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sse")
@RequireAuth
@RequiredArgsConstructor
public class UnifiedSseController {

    private final UnifiedSseEmitter unifiedSseEmitter;
    private final SessionManager sessionManager;

    @GetMapping("/unified")
    public SseEmitter stream() {
        CurrentUser user = UserContext.getCurrentUser();
        log.info("Unified SSE stream requested: userId={}", user.getUserId());
        return unifiedSseEmitter.createEmitter(user.getUserId());
    }

    @PostMapping("/subscribe")
    public RX<Void> subscribe(@RequestBody SubscribeForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        if (form.getSessionIds() == null || form.getSessionIds().isEmpty()) {
            return RX.ok();
        }

        for (String sessionId : form.getSessionIds()) {
            // Validate session belongs to current user
            try {
                Session session = sessionManager.getSession(sessionId);
                if (session == null || !user.getUserId().equals(session.getUserId())) {
                    log.debug("Subscribe skipped (not owned): userId={}, sessionId={}", user.getUserId(), sessionId);
                    continue;
                }
                unifiedSseEmitter.subscribe(user.getUserId(), sessionId);
            } catch (Exception e) {
                log.debug("Subscribe skipped (error): userId={}, sessionId={}", user.getUserId(), sessionId, e);
            }
        }
        return RX.ok();
    }

    @PostMapping("/unsubscribe")
    public RX<Void> unsubscribe(@RequestBody SubscribeForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        if (form.getSessionIds() == null || form.getSessionIds().isEmpty()) {
            return RX.ok();
        }

        for (String sessionId : form.getSessionIds()) {
            unifiedSseEmitter.unsubscribe(user.getUserId(), sessionId);
        }
        return RX.ok();
    }

    @GetMapping("/subscriptions")
    public RX<Set<String>> getSubscriptions() {
        CurrentUser user = UserContext.getCurrentUser();
        return RX.ok(unifiedSseEmitter.getSubscriptions(user.getUserId()));
    }

    @Data
    public static class SubscribeForm {
        private List<String> sessionIds;
    }
}
