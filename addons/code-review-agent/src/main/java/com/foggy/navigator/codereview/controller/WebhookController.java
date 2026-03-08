package com.foggy.navigator.codereview.controller;

import com.foggy.navigator.codereview.model.webhook.GitLabMrWebhookPayload;
import com.foggy.navigator.codereview.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * GitLab Webhook 接收端点
 * <p>
 * 不走 JWT 认证，通过 X-Gitlab-Token 头验证。
 * 立即返回 200，异步执行审核。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks/gitlab")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    /**
     * 接收 GitLab MR Webhook
     */
    @PostMapping("/code-review")
    public ResponseEntity<Map<String, Object>> handleMrWebhook(
            @RequestHeader(value = "X-Gitlab-Token", required = false) String gitlabToken,
            @RequestBody GitLabMrWebhookPayload payload) {

        if (gitlabToken == null || gitlabToken.isBlank()) {
            log.warn("Webhook received without X-Gitlab-Token header");
            return ResponseEntity.status(401)
                    .body(Map.of("received", false, "error", "Missing X-Gitlab-Token"));
        }

        boolean accepted = webhookService.processWebhook(payload, gitlabToken);

        return ResponseEntity.ok(Map.of(
                "received", true,
                "accepted", accepted
        ));
    }
}
