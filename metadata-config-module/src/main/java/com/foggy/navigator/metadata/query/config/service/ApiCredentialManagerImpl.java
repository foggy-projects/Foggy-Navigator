package com.foggy.navigator.metadata.query.config.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.ApiCredentialDTO;
import com.foggy.navigator.common.entity.ApiCredentialEntity;
import com.foggy.navigator.common.enums.AuthType;
import com.foggy.navigator.common.form.ApiCredentialForm;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.metadata.query.config.repository.ApiCredentialRepository;
import com.foggy.navigator.spi.config.ApiCredentialManager;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiCredentialManagerImpl implements ApiCredentialManager {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private final ApiCredentialRepository apiCredentialRepo;
    private final CredentialEncryptor credentialEncryptor;
    private final RestTemplateBuilder restTemplateBuilder;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public String saveCredential(String tenantId, ApiCredentialForm form) {
        log.info("Saving API credential: tenantId={}, name={}", tenantId, form.getName());

        ApiCredentialEntity entity = new ApiCredentialEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setTenantId(tenantId);
        entity.setName(form.getName());
        entity.setCategory(form.getCategory() != null ? form.getCategory() : "default");
        entity.setBaseUrl(form.getBaseUrl());
        entity.setApiKey(credentialEncryptor.encrypt(form.getApiKey()));
        entity.setAuthType(form.getAuthType() != null ? form.getAuthType() : AuthType.API_KEY);
        entity.setAuthHeaderName(form.getAuthHeaderName());
        entity.setExtraHeaders(serializeExtraHeaders(form.getExtraHeaders()));
        entity.setDescription(form.getDescription());
        entity.setIsActive(true);

        apiCredentialRepo.save(entity);
        log.info("API credential saved: id={}", entity.getId());
        return entity.getId();
    }

    @Override
    @Transactional
    public void updateCredential(String id, ApiCredentialForm form) {
        log.info("Updating API credential: id={}", id);

        ApiCredentialEntity entity = apiCredentialRepo.findById(id)
                .orElseThrow(() -> RX.throwB("API credential not found: " + id));

        if (form.getName() != null) entity.setName(form.getName());
        if (form.getCategory() != null) entity.setCategory(form.getCategory());
        if (form.getBaseUrl() != null) entity.setBaseUrl(form.getBaseUrl());
        if (form.getApiKey() != null) {
            entity.setApiKey(credentialEncryptor.encrypt(form.getApiKey()));
        }
        if (form.getAuthType() != null) entity.setAuthType(form.getAuthType());
        if (form.getAuthHeaderName() != null) entity.setAuthHeaderName(form.getAuthHeaderName());
        if (form.getExtraHeaders() != null) {
            entity.setExtraHeaders(serializeExtraHeaders(form.getExtraHeaders()));
        }
        if (form.getDescription() != null) entity.setDescription(form.getDescription());

        apiCredentialRepo.save(entity);
        log.info("API credential updated: id={}", id);
    }

    @Override
    @Transactional
    public void deleteCredential(String id) {
        log.info("Deleting API credential: id={}", id);
        apiCredentialRepo.deleteById(id);
        log.info("API credential deleted: id={}", id);
    }

    @Override
    public List<ApiCredentialDTO> listCredentials(String tenantId) {
        log.debug("Listing API credentials: tenantId={}", tenantId);
        return apiCredentialRepo.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<ApiCredentialDTO> getCredential(String id) {
        log.debug("Getting API credential: id={}", id);
        return apiCredentialRepo.findById(id).map(this::toDTO);
    }

    @Override
    public List<ApiCredentialDTO> listCredentialsByCategory(String tenantId, String category) {
        log.debug("Listing API credentials by category: tenantId={}, category={}", tenantId, category);
        return apiCredentialRepo.findByTenantIdAndCategoryOrderByCreatedAtAsc(tenantId, category).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<ApiCredentialDTO> getCredentialByName(String tenantId, String name) {
        log.debug("Getting API credential by name: tenantId={}, name={}", tenantId, name);
        return apiCredentialRepo.findByTenantIdAndName(tenantId, name).map(this::toDTO);
    }

    @Override
    public String getDecryptedApiKey(String credentialId) {
        ApiCredentialEntity entity = apiCredentialRepo.findById(credentialId)
                .orElseThrow(() -> RX.throwB("API credential not found: " + credentialId));
        return credentialEncryptor.decrypt(entity.getApiKey());
    }

    @Override
    public Map<String, String> getExtraHeaders(String credentialId) {
        ApiCredentialEntity entity = apiCredentialRepo.findById(credentialId)
                .orElseThrow(() -> RX.throwB("API credential not found: " + credentialId));
        return deserializeExtraHeaders(entity.getExtraHeaders());
    }

    @Override
    public boolean hasAnyCredential(String tenantId) {
        return apiCredentialRepo.existsByTenantId(tenantId);
    }

    @Override
    public String testConnection(String baseUrl, String apiKey, String authType, String authHeaderName) {
        log.info("Testing API connection: baseUrl={}, authType={}", baseUrl, authType);

        try {
            String testUrl = baseUrl;
            if (!testUrl.endsWith("/")) {
                testUrl += "/";
            }
            testUrl += "health";

            HttpHeaders headers = new HttpHeaders();
            AuthType type = AuthType.valueOf(authType);

            switch (type) {
                case API_KEY:
                    headers.set("X-API-Key", apiKey);
                    break;
                case BEARER_TOKEN:
                    headers.setBearerAuth(apiKey);
                    break;
                case BASIC_AUTH:
                    String[] parts = apiKey.split(":", 2);
                    if (parts.length == 2) {
                        headers.setBasicAuth(parts[0], parts[1]);
                    } else {
                        headers.setBearerAuth(apiKey);
                    }
                    break;
                case CUSTOM_HEADER:
                    String headerName = authHeaderName != null ? authHeaderName : "Authorization";
                    headers.set(headerName, apiKey);
                    break;
            }

            RestTemplate restTemplate = restTemplateBuilder
                    .connectTimeout(TEST_TIMEOUT)
                    .readTimeout(TEST_TIMEOUT)
                    .build();

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(testUrl, HttpMethod.GET, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("API connection test passed: baseUrl={}", baseUrl);
                return "连接成功";
            }

            throw RX.throwB("Unexpected response: " + response.getStatusCode());
        } catch (Exception e) {
            log.error("API connection test failed: baseUrl={}, error={}", baseUrl, e.getMessage());
            throw RX.throwB(toFriendlyTestError(e));
        }
    }

    private String serializeExtraHeaders(Map<String, String> extraHeaders) {
        if (extraHeaders == null || extraHeaders.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(extraHeaders);
        } catch (Exception e) {
            log.warn("Failed to serialize extra headers", e);
            return null;
        }
    }

    private Map<String, String> deserializeExtraHeaders(String extraHeaders) {
        if (extraHeaders == null || extraHeaders.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(extraHeaders, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize extra headers", e);
            return Map.of();
        }
    }

    private String toFriendlyTestError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "连接测试失败，请检查配置后重试";
        String lower = msg.toLowerCase();
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("invalid api key") || lower.contains("invalid_api_key")) {
            return "API Key 无效，请检查后重试";
        }
        if (lower.contains("403") || lower.contains("forbidden")) {
            return "访问被拒绝，请检查 API Key 权限或账户余额";
        }
        if (lower.contains("404") || lower.contains("not found")) {
            return "API 地址不存在，请检查 URL 是否正确";
        }
        if (lower.contains("429") || lower.contains("rate limit")) {
            return "请求过于频繁，请稍后重试";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时，请检查 API 地址是否可达";
        }
        if (lower.contains("connection refused") || lower.contains("connection reset")) {
            return "无法连接服务，请检查 API 地址是否正确";
        }
        if (lower.contains("unknown host") || lower.contains("unknownhost")) {
            return "域名解析失败，请检查 API 地址是否正确";
        }
        if (lower.contains("ssl") || lower.contains("certificate")) {
            return "SSL 证书错误，请检查 API 地址或网络环境";
        }
        return "连接测试失败: " + msg;
    }

    private ApiCredentialDTO toDTO(ApiCredentialEntity entity) {
        ApiCredentialDTO dto = new ApiCredentialDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        dto.setCategory(entity.getCategory());
        dto.setBaseUrl(entity.getBaseUrl());
        dto.setAuthType(entity.getAuthType());
        dto.setAuthHeaderName(entity.getAuthHeaderName());
        dto.setDescription(entity.getDescription());
        dto.setIsActive(entity.getIsActive());
        dto.setHasApiKey(entity.getApiKey() != null && !entity.getApiKey().isEmpty());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
