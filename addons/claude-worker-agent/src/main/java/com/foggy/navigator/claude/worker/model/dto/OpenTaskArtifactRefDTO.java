package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OpenTaskArtifactRefDTO {

    private String path;

    private String ref;

    private String summary;

    private String hash;

    private String mtime;
}
