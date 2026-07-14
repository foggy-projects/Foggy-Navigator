package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session/Task 资源归属校验的统一窄门面。
 *
 * <p>普通用户访问必须同时提供 userId 与 tenantId，并且两者都与资源持久化归属一致。
 * 本服务不提供 null=system/admin 等隐式旁路；管理员或系统调用应通过后续独立、显式的授权路径实现。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionTaskResourceAccessService {

    private static final String ACCESS_DENIED_MESSAGE = "Resource access denied";

    private final SessionRepository sessionRepository;
    private final SessionTaskRepository sessionTaskRepository;

    /**
     * 返回当前用户与租户共同拥有的 Session。
     *
     * @throws SecurityException 请求上下文不完整，或资源不存在、不属于调用方、归属字段缺失时
     */
    public SessionEntity requireOwnedSession(String sessionId, String userId, String tenantId) {
        requireAccessContext(sessionId, userId, tenantId);

        SessionEntity session = sessionRepository
                .findByIdAndUserIdAndTenantId(sessionId, userId, tenantId)
                .orElseThrow(SessionTaskResourceAccessService::accessDenied);
        if (!ownerMatches(session.getUserId(), session.getTenantId(), userId, tenantId)
                || isDeleted(session)) {
            throw accessDenied();
        }
        return session;
    }

    /**
     * 返回当前用户与租户共同拥有的 Task，并同时校验其关联 Session 的归属。
     *
     * @throws SecurityException 请求上下文不完整，或 Task/Session 任一不存在、不属于调用方、归属字段缺失时
     */
    public SessionTaskEntity requireOwnedTask(String taskId, String userId, String tenantId) {
        requireAccessContext(taskId, userId, tenantId);

        SessionTaskEntity task = sessionTaskRepository
                .findByTaskIdAndUserIdAndTenantId(taskId, userId, tenantId)
                .orElseThrow(SessionTaskResourceAccessService::accessDenied);
        if (!ownerMatches(task.getUserId(), task.getTenantId(), userId, tenantId)
                || !hasText(task.getSessionId())) {
            throw accessDenied();
        }

        SessionEntity session = sessionRepository
                .findByIdAndUserIdAndTenantId(task.getSessionId(), userId, tenantId)
                .orElseThrow(SessionTaskResourceAccessService::accessDenied);
        if (!ownerMatches(session.getUserId(), session.getTenantId(), userId, tenantId)
                || isDeleted(session)) {
            throw accessDenied();
        }
        return task;
    }

    private static void requireAccessContext(String resourceId, String userId, String tenantId) {
        if (!hasText(resourceId) || !hasText(userId) || !hasText(tenantId)) {
            throw accessDenied();
        }
    }

    private static boolean ownerMatches(String actualUserId,
                                        String actualTenantId,
                                        String expectedUserId,
                                        String expectedTenantId) {
        return hasText(actualUserId)
                && hasText(actualTenantId)
                && actualUserId.equals(expectedUserId)
                && actualTenantId.equals(expectedTenantId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isDeleted(SessionEntity session) {
        return session.getDeletedAt() != null
                || "DELETED".equalsIgnoreCase(session.getStatus());
    }

    private static SecurityException accessDenied() {
        return new SecurityException(ACCESS_DENIED_MESSAGE);
    }
}
