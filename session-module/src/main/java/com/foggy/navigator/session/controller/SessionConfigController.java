package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.session.dto.SessionConfigDTO;
import com.foggy.navigator.session.service.SessionMetadataService;
import com.foggyframework.core.ex.RX;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sessions")
@RequireAuth
@RequiredArgsConstructor
public class SessionConfigController {

    private final SessionMetadataService sessionMetadataService;

    @PatchMapping("/{sessionId}/config/tags")
    public RX<SessionConfigDTO> updateTags(@PathVariable String sessionId, @RequestBody UpdateTagsForm form) {
        return RX.ok(sessionMetadataService.updateTags(sessionId, UserContext.getCurrentUserId(), form.getTags()));
    }

    @PatchMapping("/{sessionId}/config/pin")
    public RX<SessionConfigDTO> updatePin(@PathVariable String sessionId, @RequestBody UpdatePinForm form) {
        return RX.ok(sessionMetadataService.updatePin(sessionId, UserContext.getCurrentUserId(), form.isPinned()));
    }

    @PatchMapping("/{sessionId}/config/title")
    public RX<SessionConfigDTO> updateTitle(@PathVariable String sessionId, @RequestBody UpdateTitleForm form) {
        return RX.ok(sessionMetadataService.updateTitle(sessionId, UserContext.getCurrentUserId(), form.getTitle()));
    }

    @PatchMapping("/{sessionId}/config/milestone")
    public RX<SessionConfigDTO> updateMilestone(@PathVariable String sessionId, @RequestBody UpdateMilestoneForm form) {
        return RX.ok(sessionMetadataService.updateMilestone(sessionId, UserContext.getCurrentUserId(), form.getMilestoneId()));
    }

    @PostMapping("/{sessionId}/config/bind-auth")
    public RX<SessionConfigDTO> bindAuth(@PathVariable String sessionId, @RequestBody UpdateAuthForm form) {
        return RX.ok(sessionMetadataService.bindAuth(sessionId, UserContext.getCurrentUserId(),
                form.getAuthMode(), form.getAuthToken(), form.getBaseUrl()));
    }

    @PatchMapping("/{sessionId}/config/auth")
    public RX<SessionConfigDTO> updateAuth(@PathVariable String sessionId, @RequestBody UpdateAuthForm form) {
        return RX.ok(sessionMetadataService.updateAuth(sessionId, UserContext.getCurrentUserId(),
                form.getAuthMode(), form.getAuthToken(), form.getBaseUrl()));
    }

    @GetMapping("/configs")
    public RX<List<SessionConfigDTO>> listConfigs(@RequestParam(required = false) String sessionIds) {
        return listConfigsByIds(Arrays.stream((sessionIds != null ? sessionIds : "").split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .toList());
    }

    @PostMapping("/configs")
    public RX<List<SessionConfigDTO>> listConfigsByPost(@RequestBody(required = false) ListConfigsForm form) {
        return listConfigsByIds(form != null ? form.getSessionIds() : null);
    }

    private RX<List<SessionConfigDTO>> listConfigsByIds(List<String> sessionIds) {
        List<String> ids = sessionIds != null ? sessionIds.stream()
                .map(id -> id != null ? id.trim() : "")
                .filter(id -> !id.isEmpty())
                .toList() : List.of();
        if (ids.isEmpty()) {
            return RX.ok(List.of());
        }
        return RX.ok(sessionMetadataService.listBySessionIds(UserContext.getCurrentUserId(), ids));
    }

    @PostMapping("/configs/batch-bind-auth")
    public RX<Map<String, Object>> batchBindAuth(@RequestBody BatchBindAuthForm form) {
        List<String> sessionIds = form.getSessionIds() != null ? form.getSessionIds() : List.of();
        int bound = sessionMetadataService.batchBindAuth(
                sessionIds,
                UserContext.getCurrentUserId(),
                form.getAuthMode(),
                form.getAuthToken(),
                form.getBaseUrl(),
                Boolean.TRUE.equals(form.getSkipExisting()),
                form.getModelConfigId()
        );
        return RX.ok(Map.of("bound", bound, "total", sessionIds.size()));
    }

    @PostMapping("/{sessionId}/config/archive")
    public RX<SessionConfigDTO> archiveConversation(@PathVariable String sessionId) {
        return RX.ok(sessionMetadataService.archiveConversation(sessionId, UserContext.getCurrentUserId()));
    }

    @PostMapping("/{sessionId}/config/unarchive")
    public RX<SessionConfigDTO> unarchiveConversation(@PathVariable String sessionId) {
        return RX.ok(sessionMetadataService.unarchiveConversation(sessionId, UserContext.getCurrentUserId()));
    }

    @PostMapping("/{sessionId}/config/hold")
    public RX<SessionConfigDTO> holdConversation(@PathVariable String sessionId) {
        return RX.ok(sessionMetadataService.holdConversation(sessionId, UserContext.getCurrentUserId()));
    }

    @PostMapping("/{sessionId}/config/unhold")
    public RX<SessionConfigDTO> unholdConversation(@PathVariable String sessionId) {
        return RX.ok(sessionMetadataService.unholdConversation(sessionId, UserContext.getCurrentUserId()));
    }

    @Data
    public static class UpdateTagsForm {
        private List<String> tags;
    }

    @Data
    public static class UpdatePinForm {
        private boolean pinned;
    }

    @Data
    public static class UpdateTitleForm {
        private String title;
    }

    @Data
    public static class UpdateMilestoneForm {
        private String milestoneId;
    }

    @Data
    public static class UpdateAuthForm {
        private String authMode;
        private String authToken;
        private String baseUrl;
    }

    @Data
    public static class BatchBindAuthForm {
        private List<String> sessionIds;
        private String authMode;
        private String authToken;
        private String baseUrl;
        private Boolean skipExisting;
        private String modelConfigId;
    }

    @Data
    public static class ListConfigsForm {
        private List<String> sessionIds;
    }
}
