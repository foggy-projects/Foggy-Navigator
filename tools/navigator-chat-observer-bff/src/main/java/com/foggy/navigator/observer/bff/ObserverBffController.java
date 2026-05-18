package com.foggy.navigator.observer.bff;

import com.foggy.navigator.sdk.model.AgentTask;
import com.foggy.navigator.sdk.model.SessionListPage;
import com.foggy.navigator.sdk.model.SessionMessagesPage;
import com.foggy.navigator.sdk.model.TaskMessagesPage;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class ObserverBffController {

    private final NavigatorObserverService navigatorObserverService;
    private final LocalAttachmentStorageService attachmentStorageService;

    public ObserverBffController(
            NavigatorObserverService navigatorObserverService,
            LocalAttachmentStorageService attachmentStorageService) {
        this.navigatorObserverService = navigatorObserverService;
        this.attachmentStorageService = attachmentStorageService;
    }

    @GetMapping("/api/v1/observer/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.ok(navigatorObserverService.observerConfig());
    }

    @PostMapping("/api/v1/observer/auth/login")
    public ApiResponse<Map<String, Object>> loginWithNavigatorAccount(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(navigatorObserverService.loginWithNavigatorAccount(body));
    }

    @PostMapping("/api/v1/observer/attachments")
    public ApiResponse<AttachmentDescriptor> uploadAttachment(@RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(attachmentStorageService.store(file));
    }

    @GetMapping("/api/v1/observer/attachments/{id}/{fileName:.+}")
    public ResponseEntity<Resource> readAttachment(
            @PathVariable String id,
            @PathVariable String fileName) {
        Resource resource = attachmentStorageService.load(id, fileName);
        return ResponseEntity.ok()
                .contentType(attachmentStorageService.mediaType(id, fileName))
                .headers(headers -> headers.setContentDisposition(ContentDisposition.inline()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()))
                .body(resource);
    }

    @PostMapping("/api/v1/open/agents/{agentId}/ask")
    public ApiResponse<AgentTask> ask(
            @PathVariable String agentId,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(navigatorObserverService.ask(agentId, body));
    }

    @PostMapping("/api/v1/open/agents/{agentId}/preflight")
    public ApiResponse<Object> preflight(
            @PathVariable String agentId,
            @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(navigatorObserverService.preflight(agentId, body == null ? Map.of() : body));
    }

    @GetMapping("/api/v1/open/agents/{agentId}/tasks/{taskId}")
    public ApiResponse<AgentTask> getTask(
            @PathVariable String agentId,
            @PathVariable String taskId) {
        return ApiResponse.ok(navigatorObserverService.getTask(agentId, taskId));
    }

    @GetMapping("/api/v1/open/agents/{agentId}/tasks/{taskId}/messages")
    public ApiResponse<TaskMessagesPage> getTaskMessages(
            @PathVariable String agentId,
            @PathVariable String taskId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(navigatorObserverService.getTaskMessages(agentId, taskId, limit, cursor));
    }

    @PostMapping("/api/v1/open/agents/{agentId}/tasks/{taskId}/cancel")
    public ApiResponse<Map<String, Object>> cancelTask(
            @PathVariable String agentId,
            @PathVariable String taskId) {
        return ApiResponse.ok(navigatorObserverService.cancelTask(agentId, taskId));
    }

    @GetMapping("/api/v1/open/agents/{agentId}/tasks")
    public ApiResponse<List<AgentTask>> listTasks(@PathVariable String agentId) {
        return ApiResponse.ok(navigatorObserverService.listTasks(agentId));
    }

    @GetMapping("/api/v1/open/agents/{agentId}/sessions")
    public ApiResponse<SessionListPage> listSessions(
            @PathVariable String agentId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(navigatorObserverService.listSessions(agentId, limit, cursor));
    }

    @GetMapping("/api/v1/open/agents/{agentId}/sessions/{contextId}/messages")
    public ApiResponse<SessionMessagesPage> getSessionMessages(
            @PathVariable String agentId,
            @PathVariable String contextId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(navigatorObserverService.getSessionMessages(agentId, contextId, limit, cursor));
    }
}
