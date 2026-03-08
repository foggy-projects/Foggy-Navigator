package com.foggy.navigator.codereview.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * GitLab MR diff_refs（base/start/head SHA），用于 inline discussion 定位
 */
@Data
@AllArgsConstructor
public class DiffRefs {
    private final String baseSha;
    private final String startSha;
    private final String headSha;
}
