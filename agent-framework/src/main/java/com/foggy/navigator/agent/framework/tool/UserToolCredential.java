package com.foggy.navigator.agent.framework.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户工具凭证（私有，加密存储）
 *
 * 设计原则（业界最佳实践）：
 * - 共享Agent + 用户级凭证隔离
 * - 凭证不存储在Agent配置中，独立管理
 * - 运行时通过userId动态注入凭证
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserToolCredential {
    private String id;
    private String userId;
    private String tenantId;
    private String toolName;
    private String accessToken;
    private String refreshToken;
    private Map<String, String> customHeaders;
    private Map<String, Object> metadata;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
}
