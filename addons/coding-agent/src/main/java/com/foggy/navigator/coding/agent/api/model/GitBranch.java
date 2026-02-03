package com.foggy.navigator.coding.agent.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitBranch {

    private String name;

    private boolean isDefault;

    private boolean isProtected;

    private String commitId;

    private String commitMessage;
}
