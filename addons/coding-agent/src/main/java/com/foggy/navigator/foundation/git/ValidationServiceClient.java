package com.foggy.navigator.foundation.git;

import com.foggy.navigator.foundation.git.model.ValidationRequest;
import com.foggy.navigator.foundation.git.model.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 验证服务客户端
 * 调用独立部署的语义层验证服务
 * 服务地址: http://localhost:7108
 */
@Service
@Slf4j
public class ValidationServiceClient {

    private final RestTemplate restTemplate;

    @Value("${foggy.coding-agent.validation.url:http://localhost:7108}")
    private String validationServiceUrl;

    @Value("${foggy.coding-agent.validation.enabled:true}")
    private boolean validationEnabled;

    public ValidationServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 验证语义层文件
     *
     * @param path      文件夹路径
     * @param namespace 命名空间（格式: {projectId}-{branchName}）
     * @return 验证结果
     */
    public ValidationResult validate(String path, String namespace) {
        return validate(path, namespace, null);
    }

    /**
     * 验证语义层文件（支持自定义验证服务地址）
     *
     * @param path                  文件夹路径
     * @param namespace             命名空间
     * @param customValidationUrl   自定义验证服务地址（可选）
     * @return 验证结果
     */
    public ValidationResult validate(String path, String namespace, String customValidationUrl) {
        if (!validationEnabled) {
            log.warn("验证服务已禁用，跳过验证");
            return ValidationResult.skipped();
        }

        String serviceUrl = customValidationUrl != null ? customValidationUrl : validationServiceUrl;
        log.info("调用验证服务: path={}, namespace={}, url={}", path, namespace, serviceUrl);

        try {
            ValidationRequest request = ValidationRequest.builder()
                    .path(path)
                    .namespace(namespace)
                    .watch(false)  // 默认不监听文件变化
                    .clearExisting(true)
                    .includeStackTrace(false)
                    .build();

            String url = serviceUrl + "/api/semantic-layer/validate";
            ResponseEntity<ValidationResult> response = restTemplate.postForEntity(
                    url,
                    request,
                    ValidationResult.class
            );

            ValidationResult result = response.getBody();
            if (result != null) {
                log.info("验证完成: success={}, validFiles={}/{}, errors={}",
                        result.isSuccess(),
                        result.getValidFiles(),
                        result.getTotalFiles(),
                        result.getErrors().size());
            }

            return result;

        } catch (Exception e) {
            log.error("调用验证服务失败: path={}, namespace={}", path, namespace, e);
            return ValidationResult.error("验证服务调用失败: " + e.getMessage());
        }
    }

    /**
     * 检查验证服务是否可用
     *
     * @return 是否可用
     */
    public boolean isAvailable() {
        return isAvailable(null);
    }

    /**
     * 检查验证服务是否可用（支持自定义地址）
     *
     * @param customValidationUrl 自定义验证服务地址（可选）
     * @return 是否可用
     */
    public boolean isAvailable(String customValidationUrl) {
        try {
            String serviceUrl = customValidationUrl != null ? customValidationUrl : validationServiceUrl;
            String url = serviceUrl + "/api/semantic-layer/health";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();

        } catch (Exception e) {
            log.error("验证服务不可用", e);
            return false;
        }
    }
}
