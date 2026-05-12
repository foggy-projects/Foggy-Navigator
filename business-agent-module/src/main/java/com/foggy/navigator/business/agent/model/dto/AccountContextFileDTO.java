package com.foggy.navigator.business.agent.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class AccountContextFileDTO {
    @JsonAlias("file_name")
    private String fileName;
    private boolean exists;
    private long size;
    @JsonAlias("line_count")
    private int lineCount;
    private String sha256;
    private boolean truncated;
    private boolean writable;
    private String content;
}
