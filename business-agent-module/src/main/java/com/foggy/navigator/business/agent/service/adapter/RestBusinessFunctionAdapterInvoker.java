package com.foggy.navigator.business.agent.service.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.service.ClientAppUpstreamRouteService;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class RestBusinessFunctionAdapterInvoker implements BusinessFunctionAdapterInvoker {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final Environment environment;
    private final ClientAppUserGrantService userGrantService;
    private final ClientAppUpstreamRouteService upstreamRouteService;
    private static final String UPSTREAM_PROPERTY_PREFIX = "foggy.navigator.business.agent.upstreams.";
    private static final String CONTROLLED_HEADER_PREFIX = "x-navigator-";
    private static final Set<HttpMethod> ALLOWED_METHODS = Set.of(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE
    );
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "host",
            "content-length",
            "transfer-encoding",
            "connection",
            "authorization",
            "proxy-authorization"
    );

    @Override
    public boolean supports(String type) {
        return "rest".equalsIgnoreCase(type);
    }

    @Override
    public BusinessFunctionAdapterResult invoke(BusinessFunctionRuntimeContextDTO context, String inputJson) {
        String configJson = context.getAdapterConfigJson();

        if (!StringUtils.hasText(configJson)) {
            throw new IllegalArgumentException("Adapter config is missing or blank");
        }

        try {
            JsonNode configNode = objectMapper.readTree(configJson);

            // 1. SSRF Mitigation via Environment property allowlist
            String upstreamRef = configNode.path("upstream_ref").asText(null);
            if (!StringUtils.hasText(upstreamRef)) {
                throw new IllegalArgumentException("Rest adapter requires 'upstream_ref'");
            }

            String baseUrl = environment.getProperty(UPSTREAM_PROPERTY_PREFIX + upstreamRef + ".url");
            String userTokenHeader = environment.getProperty(UPSTREAM_PROPERTY_PREFIX + upstreamRef + ".user-token-header");
            if (!StringUtils.hasText(baseUrl) && upstreamRouteService != null) {
                ClientAppUpstreamRouteService.ResolvedUpstreamRoute route = upstreamRouteService
                        .resolveEnabledRoute(context.getTenantId(), context.getClientAppId(), upstreamRef)
                        .orElse(null);
                if (route != null) {
                    baseUrl = route.getBaseUrl();
                    userTokenHeader = route.getUserTokenHeader();
                }
            }
            if (!StringUtils.hasText(baseUrl)) {
                throw new IllegalArgumentException("Unauthorized or unconfigured upstream_ref: " + upstreamRef);
            }
            userTokenHeader = trimToNull(userTokenHeader);
            validateBaseUrl(baseUrl);
            if (StringUtils.hasText(userTokenHeader)) {
                validateControlledHeaderName(userTokenHeader);
            }

            // 2. Prepare Context Node for evaluation
            ObjectNode rootEvalNode = objectMapper.createObjectNode();
            if (StringUtils.hasText(inputJson)) {
                rootEvalNode.set("input", objectMapper.readTree(inputJson));
            }
            rootEvalNode.set("context", objectMapper.valueToTree(context));

            // 3. Resolve Path
            String path = configNode.path("path").asText(null);
            validatePath(path);
            JsonNode adapterNode = configNode.path("adapter");
            JsonNode pathParamsNode = adapterNode.path("path_params");

            if (!pathParamsNode.isMissingNode() && pathParamsNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = pathParamsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String resolved = SimpleJsonPathEvaluator.evaluate(field.getValue().asText(), rootEvalNode);
                    if (resolved != null) {
                        path = path.replace("{" + field.getKey() + "}", resolved);
                    }
                }
            }

            String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            String url = normalizedBaseUrl + path;

            // 4. Resolve Headers
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
            JsonNode headersNode = adapterNode.path("headers");
            if (!headersNode.isMissingNode() && headersNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = headersNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    validateHeaderName(field.getKey(), userTokenHeader);
                    String resolved = SimpleJsonPathEvaluator.evaluate(field.getValue().asText(), rootEvalNode);
                    if (StringUtils.hasText(resolved)) {
                        httpHeaders.add(field.getKey(), resolved);
                    }
                }
            }
            injectControlledHeaders(httpHeaders, context);
            injectUserTokenHeader(httpHeaders, context, userTokenHeader);

            // 5. Resolve Body
            JsonNode bodyNode = adapterNode.path("body");
            boolean hasObjectBodyMapping = !bodyNode.isMissingNode() && bodyNode.isObject();
            Map<String, Object> resolvedBody = new HashMap<>();
            if (hasObjectBodyMapping) {
                Iterator<Map.Entry<String, JsonNode>> fields = bodyNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    resolvedBody.put(field.getKey(), resolveBodyValue(field.getValue().asText(), rootEvalNode));
                }
            }

            String methodText = configNode.path("method").asText(null);
            if (!StringUtils.hasText(methodText)) {
                throw new IllegalArgumentException("Rest adapter requires 'method'");
            }
            String methodStr = methodText.toUpperCase(Locale.ROOT);
            HttpMethod method = HttpMethod.valueOf(methodStr);
            if (!ALLOWED_METHODS.contains(method)) {
                throw new IllegalArgumentException("Unsupported REST method: " + methodStr);
            }

            Object requestBody = (HttpMethod.GET.equals(method) || HttpMethod.DELETE.equals(method) || !hasObjectBodyMapping)
                    ? null
                    : resolvedBody;
            HttpEntity<Object> requestEntity = new HttpEntity<>(requestBody, httpHeaders);

            log.info(
                    "Executing REST adapter request. functionId={}, method={}, url={}, headers={}, body={}",
                    context.getFunction().getFunctionId(),
                    method,
                    url,
                    sanitizeHeaders(httpHeaders),
                    toJsonForLog(requestBody)
            );

            // 6. Execute Request
            ResponseEntity<String> response = restTemplate.exchange(url, method, requestEntity, String.class);
            log.info(
                    "REST adapter response received. functionId={}, method={}, url={}, status={}, body={}",
                    context.getFunction().getFunctionId(),
                    method,
                    url,
                    response.getStatusCode(),
                    response.getBody()
            );
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalArgumentException("Rest adapter execution failed with HTTP "
                        + response.getStatusCode() + ": " + response.getBody());
            }
            validateBusinessEnvelopeSuccess(response.getBody());

            return BusinessFunctionAdapterResult.success(response.getBody());

        } catch (HttpStatusCodeException e) {
            // 7. Non-2xx fail-closed
            log.error("Rest adapter execution failed with HTTP {} for function {}", e.getStatusCode(), context.getFunction().getFunctionId());
            throw new IllegalArgumentException("Rest adapter execution failed with HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to execute rest adapter for function {}", context.getFunction().getFunctionId(), e);
            throw new IllegalArgumentException("Rest adapter execution failed: " + e.getMessage(), e);
        }
    }

    private void validateBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid upstream base URL", e);
        }

        if (!StringUtils.hasText(uri.getScheme())
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Upstream base URL must use http or https");
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw new IllegalArgumentException("Upstream base URL must include a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Upstream base URL must not include user info");
        }
    }

    private void validatePath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("Rest adapter requires 'path'");
        }
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://")) {
            throw new IllegalArgumentException("Rest adapter path must be an absolute path, not a URL");
        }
    }

    private void validateHeaderName(String headerName, String userTokenHeader) {
        validateControlledHeaderName(headerName);
        if (StringUtils.hasText(userTokenHeader) && userTokenHeader.equalsIgnoreCase(headerName)) {
            throw new IllegalArgumentException("Forbidden REST adapter header: " + headerName);
        }
    }

    private void validateControlledHeaderName(String headerName) {
        if (!StringUtils.hasText(headerName)) {
            throw new IllegalArgumentException("Rest adapter header name is required");
        }
        String normalized = headerName.trim().toLowerCase(Locale.ROOT);
        if (FORBIDDEN_HEADERS.contains(normalized) || normalized.startsWith(CONTROLLED_HEADER_PREFIX)) {
            throw new IllegalArgumentException("Forbidden REST adapter header: " + headerName);
        }
    }

    private void injectControlledHeaders(HttpHeaders headers, BusinessFunctionRuntimeContextDTO context) {
        setIfPresent(headers, "X-Navigator-Tenant-Id", context.getTenantId());
        setIfPresent(headers, "X-Navigator-Client-App-Id", context.getClientAppId());
        setIfPresent(headers, "X-Navigator-Upstream-User-Id", context.getUpstreamUserId());
        setIfPresent(headers, "X-Navigator-Task-Id", context.getTaskId());
        setIfPresent(headers, "X-Navigator-Session-Id", context.getSessionId());
        setIfPresent(headers, "X-Navigator-Function-Id", context.getFunctionId());
        setIfPresent(headers, "X-Navigator-Function-Version", context.getVersion());
    }

    private void injectUserTokenHeader(HttpHeaders headers, BusinessFunctionRuntimeContextDTO context, String userTokenHeader) {
        if (!StringUtils.hasText(userTokenHeader)) {
            return;
        }
        String token = userGrantService.resolveUpstreamUserToken(
                context.getTenantId(),
                context.getClientAppId(),
                context.getUpstreamUserId());
        headers.set(userTokenHeader, token);
    }

    private void setIfPresent(HttpHeaders headers, String name, String value) {
        if (StringUtils.hasText(value)) {
            headers.set(name, value);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Object resolveBodyValue(String template, JsonNode rootEvalNode) {
        JsonNode resolvedNode = SimpleJsonPathEvaluator.evaluateNode(template, rootEvalNode);
        if (resolvedNode == null) {
            return StringUtils.hasText(template) && template.startsWith("$.") ? null : template;
        }
        return objectMapper.convertValue(resolvedNode, Object.class);
    }

    private void validateBusinessEnvelopeSuccess(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception ignored) {
            return;
        }
        if (root == null || !root.isObject()) {
            return;
        }
        Integer code = parseBusinessEnvelopeCode(root.get("code"));
        if (code == null || isBusinessEnvelopeSuccessCode(code)) {
            return;
        }
        String exCode = firstText(root, "exCode", "errorCode");
        String message = firstText(root, "msg", "message", "error", "errorMessage");
        StringBuilder builder = new StringBuilder("Rest adapter execution failed with business code ")
                .append(code);
        if (StringUtils.hasText(exCode)) {
            builder.append(" (").append(exCode).append(")");
        }
        if (StringUtils.hasText(message)) {
            builder.append(": ").append(message);
        }
        throw new IllegalArgumentException(builder.toString());
    }

    private Integer parseBusinessEnvelopeCode(JsonNode codeNode) {
        if (codeNode == null || codeNode.isNull()) {
            return null;
        }
        if (codeNode.canConvertToInt()) {
            return codeNode.asInt();
        }
        if (codeNode.isTextual()) {
            String value = codeNode.asText();
            if (!StringUtils.hasText(value)) {
                return null;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean isBusinessEnvelopeSuccessCode(int code) {
        return code == 0 || (code >= 200 && code < 300);
    }

    private String firstText(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode value = root.get(field);
            if (value != null && value.isValueNode() && StringUtils.hasText(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }

    private Map<String, List<String>> sanitizeHeaders(HttpHeaders headers) {
        Map<String, List<String>> sanitized = new HashMap<>();
        headers.forEach((name, values) -> {
            List<String> sanitizedValues = new ArrayList<>();
            for (String value : values) {
                sanitizedValues.add(isSensitiveHeader(name) ? maskSecret(value) : value);
            }
            sanitized.put(name, sanitizedValues);
        });
        return sanitized;
    }

    private boolean isSensitiveHeader(String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("authorization")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("api-key")
                || normalized.contains("apikey")
                || normalized.contains("cookie");
    }

    private String maskSecret(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        int length = value.length();
        if (length <= 8) {
            return "***";
        }
        return value.substring(0, 4) + "..." + value.substring(length - 4) + "(len=" + length + ")";
    }

    private String toJsonForLog(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
