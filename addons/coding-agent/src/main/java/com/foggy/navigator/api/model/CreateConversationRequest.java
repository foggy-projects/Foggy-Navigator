package com.foggy.navigator.api.model;

import com.foggy.navigator.foundation.git.model.GitCredentials;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateConversationRequest {

    private String userId;

    private String projectId;

    private String gitRepoUrl;

    private String branchName;

    private GitCredentials gitCredentials;

    private String initialMessage;
}
