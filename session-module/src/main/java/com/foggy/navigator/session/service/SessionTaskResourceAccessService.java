package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Session/Task 资源归属校验的统一窄门面。
 *
 * <p>租户主体访问必须同时匹配 userId 与 tenantId。身份模型允许本地/平台账号没有 tenantId，
 * 此时只能访问同 userId 且 tenantId 同样为 null 的资源。tenantless owner scope 不是管理员旁路，
 * 不允许跨用户或访问任何租户绑定资源；管理员跨主体和系统调用仍须使用后续独立、显式的授权路径。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionTaskResourceAccessService {

    private static final String ACCESS_DENIED_MESSAGE = "Resource access denied";

    private final SessionRepository sessionRepository;
    private final SessionTaskRepository sessionTaskRepository;

    /**
     * 返回当前主体拥有的 Session。
     *
     * @throws SecurityException 请求上下文缺少资源或用户，或资源不存在、不属于调用方、归属字段冲突时
     */
    public SessionEntity requireOwnedSession(String sessionId, String userId, String tenantId) {
        requireAccessContext(sessionId, userId);

        SessionEntity session = findOwnedSession(sessionId, userId, tenantId)
                .orElseThrow(SessionTaskResourceAccessService::accessDenied);
        if (!ownerMatches(session.getUserId(), session.getTenantId(), userId, tenantId)
                || isDeleted(session)) {
            throw accessDenied();
        }
        return session;
    }

    /**
     * 返回当前主体拥有的 Task，并同时校验其关联 Session 的归属。
     *
     * @throws SecurityException 请求上下文缺少资源或用户，或 Task/Session 任一不存在、不属于调用方、归属字段冲突时
     */
    public SessionTaskEntity requireOwnedTask(String taskId, String userId, String tenantId) {
        requireAccessContext(taskId, userId);

        SessionTaskEntity task = findOwnedTask(taskId, userId, tenantId)
                .orElseThrow(SessionTaskResourceAccessService::accessDenied);
        if (!ownerMatches(task.getUserId(), task.getTenantId(), userId, tenantId)
                || !hasText(task.getSessionId())) {
            throw accessDenied();
        }

        SessionEntity session = findOwnedSession(task.getSessionId(), userId, tenantId)
                .orElseThrow(SessionTaskResourceAccessService::accessDenied);
        if (!ownerMatches(session.getUserId(), session.getTenantId(), userId, tenantId)
                || isDeleted(session)) {
            throw accessDenied();
        }
        return task;
    }

    /**
     * Resolves a management-visible Task by tenant while preserving its durable owner.
     *
     * <p>This is not an owner bypass for ordinary callers. It is a narrow input to the trusted
     * OpenAPI management adapter, which binds the returned owner into the canonical command and
     * Provider plan. Tenantless Tasks and inconsistent Task/Session ownership fail closed.</p>
     */
    ManagedTaskIdentity requireTenantTask(String taskId, String tenantId) {
        if (!hasText(taskId) || !hasText(tenantId)) {
            throw accessDenied();
        }
        SessionTaskEntity task = sessionTaskRepository.findByTaskId(taskId)
                .orElseThrow(SessionTaskResourceAccessService::accessDenied);
        if (!taskId.equals(task.getTaskId())
                || !tenantId.equals(task.getTenantId())
                || !hasText(task.getUserId())
                || !hasText(task.getSessionId())
                || !hasText(task.getAgentId())) {
            throw accessDenied();
        }
        SessionEntity session = sessionRepository
                .findByIdAndUserIdAndTenantId(
                        task.getSessionId(), task.getUserId(), tenantId)
                .orElseThrow(SessionTaskResourceAccessService::accessDenied);
        if (!task.getSessionId().equals(session.getId())
                || !task.getUserId().equals(session.getUserId())
                || !tenantId.equals(session.getTenantId())
                || isDeleted(session)) {
            throw accessDenied();
        }
        return new ManagedTaskIdentity(
                task.getTaskId(),
                task.getSessionId(),
                task.getUserId(),
                task.getTenantId(),
                task.getAgentId());
    }

    private Optional<SessionEntity> findOwnedSession(String sessionId,
                                                     String userId,
                                                     String tenantId) {
        if (hasText(tenantId)) {
            return sessionRepository.findByIdAndUserIdAndTenantId(sessionId, userId, tenantId);
        }
        return sessionRepository.findTenantlessByIdAndUserId(sessionId, userId);
    }

    private Optional<SessionTaskEntity> findOwnedTask(String taskId,
                                                      String userId,
                                                      String tenantId) {
        if (hasText(tenantId)) {
            return sessionTaskRepository.findByTaskIdAndUserIdAndTenantId(taskId, userId, tenantId);
        }
        return sessionTaskRepository.findTenantlessByTaskIdAndUserId(taskId, userId);
    }

    private static void requireAccessContext(String resourceId, String userId) {
        if (!hasText(resourceId) || !hasText(userId)) {
            throw accessDenied();
        }
    }

    private static boolean ownerMatches(String actualUserId,
                                        String actualTenantId,
                                        String expectedUserId,
                                        String expectedTenantId) {
        if (!hasText(actualUserId) || !actualUserId.equals(expectedUserId)) {
            return false;
        }
        return hasText(expectedTenantId)
                ? expectedTenantId.equals(actualTenantId)
                : !hasText(actualTenantId);
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

    record ManagedTaskIdentity(
            String taskId,
            String sessionId,
            String ownerUserId,
            String tenantId,
            String logicalAgentId) {
        ManagedTaskIdentity {
            if (!hasText(taskId)
                    || !hasText(sessionId)
                    || !hasText(ownerUserId)
                    || !hasText(tenantId)
                    || !hasText(logicalAgentId)) {
                throw new IllegalArgumentException(
                        "managed Task identity must be complete");
            }
        }

        @Override
        public String toString() {
            return "ManagedTaskIdentity[content-free]";
        }
    }
}
