package com.foggy.navigator.coding.agent.git;

import com.foggy.navigator.coding.agent.api.model.GitBranch;
import com.foggy.navigator.coding.agent.api.model.GitProject;
import com.foggy.navigator.coding.agent.api.model.entity.GitCredentialEntity;

import java.util.List;

public interface GitProviderService {

    List<GitProject> listProjects(GitCredentialEntity credential, String search, int page, int perPage);

    GitProject getProject(GitCredentialEntity credential, String projectId);

    List<GitBranch> listBranches(GitCredentialEntity credential, String projectId, String search);

    boolean testConnection(GitCredentialEntity credential);
}
