package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.session.dto.SessionConfigDTO;
import com.foggy.navigator.session.service.SessionMetadataService;
import com.foggy.navigator.session.service.SessionTaskResourceAccessService;
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
    private final SessionTaskResourceAccessService resourceAccessService;

    @PatchMapping("/{sessionId}/config/tags")
    public RX<SessionConfigDTO> updateTags(@PathVariable String sessionId, @RequestBody UpdateTagsForm form) {
        CurrentUser user = requireOwnedSession(sessionId);
        return RX.ok(sessionMetadataService.updateTags(sessionId, user.getUserId(), form.getTags()));
    }

    @PatchMapping("/{sessionId}/config/pin")
    public RX<SessionConfigDTO> updatePin(@PathVariable String sessionId, @RequestBody UpdatePinForm form) {
        CurrentUser user = requireOwnedSession(sessionId);
        return RX.ok(sessionMetadataService.updatePin(sessionId, user.getUserId(), form.isPinned()));
    }

    @PatchMapping("/{sessionId}/config/title")
    public RX<SessionConfigDTO> updateTitle(@PathVariable String sessionId, @RequestBody UpdateTitleForm form) {
        CurrentUser user = requireOwnedSession(sessionId);
        return RX.ok(sessionMetadataService.updateTitle(sessionId, user.getUserId(), form.getTitle()));
    }

    @PatchMapping("/{sessionId}/config/milestone")
    public RX<SessionConfigDTO> updateMilestone(@PathVariable String sessionId, @RequestBody UpdateMilestoneForm form) {
        CurrentUser user = requireOwnedSession(sessionId);
        return RX.ok(sessionMetadataService.updateMilestone(sessionId, user.getUserId(), form.getMilestoneId()));
    }

    @PostMapping("/{sessionId}/config/bind-auth")
    public RX<SessionConfigDTO> bindAuth(@PathVariable String sessionId, @RequestBody UpdateAuthForm form) {
        CurrentUser user = requireOwnedSession(sessionId);
        return RX.ok(sessionMetadataService.bindAuth(sessionId, user.getUserId(),
                form.getAuthMode(), form.getAuthToken(), form.getBaseUrl(), form.getModelConfigId()));
    }

    @PatchMapping("/{sessionId}/config/auth")
    public RX<SessionConfigDTO> updateAuth(@PathVariable String sessionId, @RequestBody UpdateAuthForm form) {
        CurrentUser user = requireOwnedSession(sessionId);
        return RX.ok(sessionMetadataService.updateAuth(sessionId, user.getUserId(),
                form.getAuthMode(), form.getAuthToken(), form.getBaseUrl(), form.getModelConfigId()));
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
        CurrentUser user = UserContext.getCurrentUser();
        requireOwnedSessions(ids, user);
        return RX.ok(sessionMetadataService.listBySessionIds(user.getUserId(), ids));
    }

    @PostMapping("/configs/batch-bind-auth")
    public RX<Map<String, Object>> batchBindAuth(@RequestBody BatchBindAuthForm form) {
        List<String> sessionIds = form.getSessionIds() != null ? form.getSessionIds() : List.of();
        CurrentUser user = UserContext.getCurrentUser();
        requireOwnedSessions(sessionIds, user);
        int bound = sessionMetadataService.batchBindAuth(
                sessionIds,
                user.getUserId(),
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
        CurrentUser user = requireOwnedSession(sessionId);
        return RX.ok(sessionMetadataService.archiveConversation(sessionId, user.getUserId()));
    }

    @PostMapping("/{sessionId}/config/unarchive")
    public RX<SessionConfigDTO> unarchiveConversation(@PathVariable String sessionId) {
        CurrentUser user = requireOwnedSession(sessionId);
        return RX.ok(sessionMetadataService.unarchiveConversation(sessionId, user.getUserId()));
    }

    @PostMapping("/{sessionId}/config/hold")
    public RX<SessionConfigDTO> holdConversation(@PathVariable String sessionId) {
        CurrentUser user = requireOwnedSession(sessionId);
        return RX.ok(sessionMetadataService.holdConversation(sessionId, user.getUserId()));
    }

    @PostMapping("/{sessionId}/config/unhold")
    public RX<SessionConfigDTO> unholdConversation(@PathVariable String sessionId) {
        CurrentUser user = requireOwnedSession(sessionId);
        return RX.ok(sessionMetadataService.unholdConversation(sessionId, user.getUserId()));
    }

    private CurrentUser requireOwnedSession(String sessionId) {
        CurrentUser user = UserContext.getCurrentUser();
        resourceAccessService.requireOwnedSession(sessionId, user.getUserId(), user.getTenantId());
        return user;
    }

    private void requireOwnedSessions(List<String> sessionIds, CurrentUser user) {
        // P3 基线逐项校验；若批量规模增长，再以 tenant-scoped bulk query 消除 N+1。
        for (String sessionId : sessionIds) {
            resourceAccessService.requireOwnedSession(sessionId, user.getUserId(), user.getTenantId());
        }
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
        private String modelConfigId;
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
