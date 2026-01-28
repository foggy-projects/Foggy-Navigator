package com.foggy.navigator.api.controller;

import com.foggy.navigator.api.model.Conversation;
import com.foggy.navigator.api.service.ConversationRecoveryService;
import com.foggy.navigator.api.service.ConversationService;
import com.foggy.navigator.common.annotation.RequireAuth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/recovery")
@RequireAuth
@Slf4j
public class ConversationRecoveryController {

    @Autowired
    private ConversationRecoveryService conversationRecoveryService;

    @Autowired
    private ConversationService conversationService;

    @PostMapping("/conversations/{conversationId}")
    public ResponseEntity<Conversation> recoverConversation(@PathVariable String conversationId) {
        log.info("恢复会话请求: conversationId={}", conversationId);
        try {
            conversationRecoveryService.recoverConversation(conversationId);
            Conversation conversation = conversationService.getConversation(conversationId);
            return ResponseEntity.ok(conversation);
        } catch (Exception e) {
            log.error("恢复会话失败: conversationId={}", conversationId, e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/conversations")
    public ResponseEntity<List<Conversation>> recoverAllConversations() {
        log.info("恢复所有会话请求");
        try {
            List<Conversation> recovered = conversationRecoveryService.recoverAllConversations();
            return ResponseEntity.ok(recovered);
        } catch (Exception e) {
            log.error("恢复所有会话失败", e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/cleanup")
    public ResponseEntity<Void> cleanupStoppedConversations() {
        log.info("清理已停止会话请求");
        try {
            conversationRecoveryService.cleanupStoppedConversations();
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("清理已停止会话失败", e);
            return ResponseEntity.badRequest().build();
        }
    }
}
