package com.foggy.navigator.coding.agent.api.controller;

import com.foggy.navigator.coding.agent.api.model.Conversation;
import com.foggy.navigator.coding.agent.api.model.Conversation.ConversationStatus;
import com.foggy.navigator.coding.agent.api.model.CreateConversationRequest;
import com.foggy.navigator.coding.agent.api.service.ConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations")
@Slf4j
public class ConversationController {

    @Autowired
    private ConversationService conversationService;

    @PostMapping
    public ResponseEntity<Conversation> createConversation(@RequestBody CreateConversationRequest request) {
        log.info("POST /api/v1/conversations - 创建对话: userId={}, projectId={}", request.getUserId(), request.getProjectId());
        Conversation conversation = conversationService.createConversation(request);
        return ResponseEntity.ok(conversation);
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<Conversation> getConversation(@PathVariable String conversationId) {
        log.info("GET /api/v1/conversations/{} - 获取对话", conversationId);
        Conversation conversation = conversationService.getConversation(conversationId);
        if (conversation == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(conversation);
    }

    @GetMapping
    public ResponseEntity<List<Conversation>> listConversations(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) ConversationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("GET /api/v1/conversations - 查询对话列表: userId={}, projectId={}, status={}, page={}, size={}",
                userId, projectId, status, page, size);
        List<Conversation> conversations = conversationService.listConversations(userId, projectId, status, page, size);
        return ResponseEntity.ok(conversations);
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId) {
        log.info("DELETE /api/v1/conversations/{} - 删除对话", conversationId);
        conversationService.deleteConversation(conversationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{conversationId}/stop")
    public ResponseEntity<Void> stopConversation(@PathVariable String conversationId) {
        log.info("POST /api/v1/conversations/{}/stop - 停止对话", conversationId);
        conversationService.stopConversation(conversationId);
        return ResponseEntity.noContent().build();
    }
}
