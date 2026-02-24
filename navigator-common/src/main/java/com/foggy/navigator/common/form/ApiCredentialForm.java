package com.foggy.navigator.common.form;

import com.foggy.navigator.common.enums.AuthType;
import lombok.Data;

import java.util.Map;

/**
 * API 凭证表单
 */
@Data
public class ApiCredentialForm {

    private String name;

    private String category;

    private String baseUrl;

    private String apiKey;

    private AuthType authType;

    private String authHeaderName;

    private Map<String, String> extraHeaders;

    private String description;
}
