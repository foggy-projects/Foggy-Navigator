package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapApprovalActor;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NaviOperatorCredentialService {

    public static final String HEADER_OPERATOR_KEY = "X-Navi-Operator-Key";
    public static final String HEADER_OPERATOR_API_KEY = "X-Navi-Operator-Api-Key";

    private static final String PROPERTY_OPERATOR_KEY_HASH = "foggy.navigator.operator.api-key-hash";
    private static final String PROPERTY_BUSINESS_OPERATOR_KEY_HASH = "foggy.navigator.business-agent.operator.api-key-hash";
    private static final String ENV_OPERATOR_KEY_HASH = "NAVI_OPERATOR_API_KEY_SHA256";
    private static final String PROPERTY_OPERATOR_CREDENTIAL_ID = "foggy.navigator.operator.credential-id";

    private final Environment environment;

    public UpstreamBootstrapApprovalActor requireAdminOrOperator(HttpServletRequest request) {
        CurrentUser user = UserContext.getCurrentUser();
        if (user != null && (user.isTenantAdmin() || user.isSuperAdmin())) {
            return UpstreamBootstrapApprovalActor.builder()
                    .operator(false)
                    .superAdmin(user.isSuperAdmin())
                    .tenantAdmin(user.isTenantAdmin())
                    .tenantId(user.getTenantId())
                    .userId(user.getUserId())
                    .build();
        }

        String operatorKey = resolveOperatorKey(request);
        if (StringUtils.hasText(operatorKey)) {
            String operatorKeyHash = SecretTokenSupport.sha256(operatorKey);
            String matchedHash = configuredHashes().stream()
                    .filter(hash -> constantTimeEquals(operatorKeyHash, hash))
                    .findFirst()
                    .orElse(null);
            if (matchedHash != null) {
                return UpstreamBootstrapApprovalActor.builder()
                        .operator(true)
                        .operatorCredentialId(resolveOperatorCredentialId(matchedHash))
                        .build();
            }
        }

        throw new SecurityException("navigator admin or operator credential is required");
    }

    private String resolveOperatorKey(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String key = request.getHeader(HEADER_OPERATOR_KEY);
        if (!StringUtils.hasText(key)) {
            key = request.getHeader(HEADER_OPERATOR_API_KEY);
        }
        return key;
    }

    private List<String> configuredHashes() {
        return Arrays.asList(
                        environment.getProperty(PROPERTY_OPERATOR_KEY_HASH),
                        environment.getProperty(PROPERTY_BUSINESS_OPERATOR_KEY_HASH),
                        environment.getProperty(ENV_OPERATOR_KEY_HASH))
                .stream()
                .filter(StringUtils::hasText)
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String resolveOperatorCredentialId(String matchedHash) {
        String configuredId = environment.getProperty(PROPERTY_OPERATOR_CREDENTIAL_ID);
        if (StringUtils.hasText(configuredId)) {
            return configuredId;
        }
        return "operator:" + suffix(matchedHash);
    }

    private boolean constantTimeEquals(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private String suffix(String value) {
        if (!StringUtils.hasText(value) || value.length() <= 8) {
            return value;
        }
        return value.substring(value.length() - 8);
    }
}
