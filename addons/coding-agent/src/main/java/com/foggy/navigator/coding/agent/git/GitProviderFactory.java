package com.foggy.navigator.coding.agent.git;

import com.foggy.navigator.coding.agent.api.model.entity.GitProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GitProviderFactory {

    @Autowired
    private GitLabClient gitLabClient;

    @Autowired
    private GitHubClient gitHubClient;

    public GitProviderService getService(GitProvider provider) {
        return switch (provider) {
            case GITLAB -> gitLabClient;
            case GITHUB -> gitHubClient;
        };
    }
}
