package com.foggy.navigator.coding.agent.git.util;

/**
 * Namespace 生成工具
 * 用于生成验证服务的命名空间，隔离不同环境
 */
public class NamespaceGenerator {

    /**
     * 生成 namespace
     * 格式: {projectId}-{branchName}
     *
     * @param projectId  项目ID
     * @param branchName 分支名称
     * @return namespace
     */
    public static String generate(String projectId, String branchName) {
        if (projectId == null || projectId.isEmpty()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        if (branchName == null || branchName.isEmpty()) {
            throw new IllegalArgumentException("branchName 不能为空");
        }

        // 清理分支名称中的特殊字符
        String cleanBranchName = branchName
                .replaceAll("[^a-zA-Z0-9-_]", "-")
                .toLowerCase();

        return projectId + "-" + cleanBranchName;
    }

    /**
     * 生成 namespace（使用用户ID和会话ID）
     * 格式: user-{userId}-{sessionId}
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return namespace
     */
    public static String generateForSession(String userId, String sessionId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }

        return "user-" + userId + "-" + sessionId;
    }
}
