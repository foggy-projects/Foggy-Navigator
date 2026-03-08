package com.foggy.navigator.coding.agent.api.model;

import com.foggy.navigator.coding.agent.api.model.entity.GitProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateGitCredentialRequest {

    private GitProvider provider;

    private String serverUrl;

    private String displayName;

    private String accessToken;

    private String refreshToken;
}
