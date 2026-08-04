package com.foggy.navigator.workbench.fap.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.foggy.agent.contract.access.v1alpha1.CatalogPage;
import com.foggy.agent.contract.runtime.v1alpha1.OperationAccepted;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.ContinueConversationForm;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.ConversationView;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.OperationForm;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.StartConversationForm;
import com.foggy.navigator.workbench.fap.service.WorkbenchFapService;
import com.foggyframework.core.ex.RX;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Separate transport for new FAP panes; no legacy Session/Task endpoint delegates here. */
@RestController
@RequestMapping("/api/v1/workbench/fap")
@ConditionalOnProperty(
        prefix = "navigator.workbench.fap",
        name = "enabled",
        havingValue = "true")
public class WorkbenchFapController {
    private final WorkbenchFapService workbench;

    public WorkbenchFapController(WorkbenchFapService workbench) {
        this.workbench = workbench;
    }

    @GetMapping("/catalog")
    public RX<CatalogPage> catalog(
            @RequestParam(value = "resourceType", required = false) String resourceType) {
        return RX.ok(workbench.catalog(userId(), resourceType));
    }

    @GetMapping("/conversations")
    public RX<List<ConversationView>> conversations() {
        return RX.ok(workbench.list(userId()));
    }

    @PostMapping("/conversations")
    public RX<ConversationView> start(@RequestBody StartConversationForm form) {
        return RX.ok(workbench.start(userId(), form));
    }

    @GetMapping("/conversations/{conversationId}")
    public RX<ConversationView> conversation(@PathVariable String conversationId) {
        return RX.ok(workbench.get(userId(), conversationId));
    }

    @PostMapping("/conversations/{conversationId}/tasks")
    public RX<ConversationView> continueConversation(
            @PathVariable String conversationId,
            @RequestBody ContinueConversationForm form) {
        return RX.ok(workbench.continueConversation(userId(), conversationId, form));
    }

    @PostMapping("/conversations/{conversationId}:cancel")
    public RX<OperationAccepted> cancel(
            @PathVariable String conversationId, @RequestBody OperationForm form) {
        return RX.ok(workbench.cancel(userId(), conversationId, form));
    }

    @PostMapping("/conversations/{conversationId}:reattach")
    public RX<OperationAccepted> reattach(
            @PathVariable String conversationId, @RequestBody OperationForm form) {
        return RX.ok(workbench.reattach(userId(), conversationId, form));
    }

    @GetMapping("/conversations/{conversationId}/events")
    public RX<JsonNode> events(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "100") int limit) {
        return RX.ok(workbench.events(userId(), conversationId, afterSeq, limit));
    }

    @GetMapping("/conversations/{conversationId}/resources")
    public RX<JsonNode> resources(@PathVariable String conversationId) {
        return RX.ok(workbench.resources(userId(), conversationId));
    }

    @GetMapping("/conversations/{conversationId}/recovery")
    public RX<JsonNode> recovery(@PathVariable String conversationId) {
        return RX.ok(workbench.recovery(userId(), conversationId));
    }

    private String userId() {
        return UserContext.getCurrentUserId();
    }
}
