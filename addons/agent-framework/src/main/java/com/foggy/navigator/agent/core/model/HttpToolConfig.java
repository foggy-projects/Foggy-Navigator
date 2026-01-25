package com.foggy.navigator.agent.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * HTTP工具配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HttpToolConfig {
    private String method;
    private String url;
    private Map<String, String> headers;
}
