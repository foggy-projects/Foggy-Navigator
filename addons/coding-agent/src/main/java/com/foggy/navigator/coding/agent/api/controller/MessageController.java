package com.foggy.navigator.coding.agent.api.controller;

import com.foggy.navigator.coding.agent.api.model.Message;
import com.foggy.navigator.coding.agent.api.model.SendMessageRequest;
import com.foggy.navigator.coding.agent.api.service.MessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
@Slf4j
public class MessageController {

    @Autowired
    private MessageService messageService;

    @PostMapping
    public ResponseEntity<Message> sendMessage(
            @PathVariable String conversationId,
            @RequestBody SendMessageRequest request) {
        log.info("POST /api/v1/conversations/{}/messages - 发送消息: content={}", conversationId, request.getContent());
        Message message = messageService.sendMessage(conversationId, request);
        return ResponseEntity.ok(message);
    }

    @GetMapping
    public ResponseEntity<List<Message>> getMessages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "10") int limit) {
        log.info("GET /api/v1/conversations/{}/messages - 获取消息: limit={}", conversationId, limit);
        List<Message> messages = messageService.getMessages(conversationId, limit);
        return ResponseEntity.ok(messages);
    }
}
