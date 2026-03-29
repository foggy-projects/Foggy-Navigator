package com.foggy.navigator.common.util;

/**
 * 目录隐式 Agent ID 工具类。
 * <p>
 * 无绑定 CodingAgent 的工作目录以 "directory#{directoryId}" 作为合成 agentId，
 * 使前端统一按 agentId 路由，后端按前缀区分真实 Agent 和目录隐式 Agent。
 */
public final class DirectoryAgentId {

    public static final String PREFIX = "directory#";

    private DirectoryAgentId() {}

    /** 判断 agentId 是否为目录合成的隐式 Agent */
    public static boolean isDirectoryAgent(String agentId) {
        return agentId != null && agentId.startsWith(PREFIX);
    }

    /** 从合成 agentId 中提取 directoryId */
    public static String extractDirectoryId(String agentId) {
        if (!isDirectoryAgent(agentId)) {
            throw new IllegalArgumentException("Not a directory agent ID: " + agentId);
        }
        return agentId.substring(PREFIX.length());
    }

    /** 为 directoryId 合成隐式 agentId */
    public static String of(String directoryId) {
        return PREFIX + directoryId;
    }
}
