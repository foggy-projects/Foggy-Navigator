package com.foggy.navigator.coding.agent.api.model;

import com.foggy.navigator.coding.agent.api.model.entity.GitCredentialEntity;
import com.foggy.navigator.coding.agent.api.model.entity.GitProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitCredentialResponse {

    private String credentialId;
    private String userId;
    private GitProvider provider;
    private String serverUrl;
    private String displayName;
    private boolean hasRefreshToken;
    private LocalDateTime tokenExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static GitCredentialResponse from(GitCredentialEntity entity) {
        return GitCredentialResponse.builder()
                .credentialId(entity.getCredentialId())
                .userId(entity.getUserId())
                .provider(entity.getProvider())
                .serverUrl(entity.getServerUrl())
                .displayName(entity.getDisplayName())
                .hasRefreshToken(entity.getRefreshToken() != null && !entity.getRefreshToken().isEmpty())
                .tokenExpiresAt(entity.getTokenExpiresAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
