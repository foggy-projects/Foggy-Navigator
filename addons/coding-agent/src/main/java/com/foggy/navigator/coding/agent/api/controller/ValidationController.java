package com.foggy.navigator.coding.agent.api.controller;

import com.foggy.navigator.coding.agent.api.service.ValidationService;
import com.foggy.navigator.coding.agent.git.model.OpenHandsEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/validation")
@Slf4j
public class ValidationController {

    @Autowired
    private ValidationService validationService;

    @PostMapping
    public ResponseEntity<Void> triggerValidation(@PathVariable String conversationId) {
        log.info("POST /api/v1/conversations/{}/validation - 触发验证", conversationId);
        validationService.triggerValidation(conversationId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/results")
    public ResponseEntity<List<OpenHandsEvent>> getValidationResults(@PathVariable String conversationId) {
        log.info("GET /api/v1/conversations/{}/validation/results - 获取验证结果", conversationId);
        List<OpenHandsEvent> results = validationService.getValidationResults(conversationId);
        return ResponseEntity.ok(results);
    }
}
