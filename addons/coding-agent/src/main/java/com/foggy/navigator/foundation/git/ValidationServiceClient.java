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
 * 调用独立部署的 Validation Service 进行语义层文件验证
 */
@Service
@Slf4j
public class ValidationServiceClient {

    private final RestTemplate restTemplate;

    @Value("${foggy.coding-agent.validation.url:http://validation-service:8081}")
    private String validationServiceUrl;

    @Value("${foggy.coding-agent.validation.enabled:true}")
    private boolean validationEnabled;

    public ValidationServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 验证语义层文件
     *
     * @param workspacePath 语义层文件目录路径
     * @return 验证结果
     */
    public ValidationResult validate(String workspacePath) {
        if (!validationEnabled) {
            log.warn("验证服务已禁用，跳过验证");
            return ValidationResult.skipped();
        }

        log.info("调用验证服务: workspacePath={}", workspacePath);

        try {
            // TODO: 等待验证服务提供详细的输入输出规范
            // 当前使用简化的请求格式
            ValidationRequest request = ValidationRequest.builder()
                    .workspacePath(workspacePath)
                    .build();

            String url = validationServiceUrl + "/api/validation/validate";
            ResponseEntity<ValidationResult> response = restTemplate.postForEntity(
                    url,
                    request,
                    ValidationResult.class
            );

            ValidationResult result = response.getBody();
            log.info("验证完成: success={}", result != null && result.isSuccess());

            return result;

        } catch (Exception e) {
            log.error("调用验证服务失败: workspacePath={}", workspacePath, e);
            return ValidationResult.error("验证服务调用失败: " + e.getMessage());
        }
    }

    /**
     * 检查验证服务是否可用
     *
     * @return 是否可用
     */
    public boolean isAvailable() {
        try {
            String url = validationServiceUrl + "/api/validation/health";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();

        } catch (Exception e) {
            log.error("验证服务不可用", e);
            return false;
        }
    }
}
