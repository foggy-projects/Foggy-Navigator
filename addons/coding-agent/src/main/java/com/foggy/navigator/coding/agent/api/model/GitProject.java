package com.foggy.navigator.coding.agent.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitProject {

    private String id;

    private String name;

    private String path;

    private String pathWithNamespace;

    private String description;

    private String httpUrlToRepo;

    private String sshUrlToRepo;

    private String defaultBranch;

    private String avatarUrl;

    private String webUrl;
}
