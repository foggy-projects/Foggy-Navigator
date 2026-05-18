package com.foggy.navigator.observer.bff;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.foggy.navigator.sdk.NavigatorClient;
import com.foggy.navigator.sdk.exception.NavigatorApiException;
import com.foggy.navigator.sdk.model.businessagent.ClientAppDTO;
import com.foggy.navigator.sdk.model.businessagent.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.sdk.model.businessagent.CreateClientAppForm;
import com.foggy.navigator.sdk.model.businessagent.GrantModelConfigForm;
import com.foggy.navigator.sdk.model.businessagent.GrantSkillToClientAppForm;
import com.foggy.navigator.sdk.model.businessagent.GrantUpstreamUserForm;
import com.foggy.navigator.sdk.model.businessagent.IssueProvisioningCredentialForm;
import com.foggy.navigator.sdk.model.businessagent.IssueRuntimeCredentialForm;
import com.foggy.navigator.sdk.model.businessagent.IssuedCredentialDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class NavigatorAccountLoginBootstrapService {

    private static final String ENABLED = "ENABLED";
    private static final String ACTIVE = "ACTIVE";

    private final ObserverBffProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public NavigatorAccountLoginBootstrapService(ObserverBffProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.sdkTimeout())
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public ObserverRuntimeCredential loginAndBootstrap(Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        String username = text(request.get("username"));
        String password = text(request.get("password"));
        if (ObserverBffProperties.isBlank(username) || ObserverBffProperties.isBlank(password)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username and password are required");
        }

        String navigatorBaseUrl = normalizeBaseUrl(defaultIfBlank(firstText(request.get("navigatorBaseUrl")),
                properties.navigatorBaseUrl()));
        LoginSession session = login(navigatorBaseUrl, username, password);

        List<String> steps = new ArrayList<>();
        ControlSession controlSession = resolveControlSession(navigatorBaseUrl, session, request, steps);
        NavigatorClient adminClient = buildControlClient(navigatorBaseUrl, controlSession);
        boolean revokeAttempted = false;

        try {
            ClientAppDTO clientApp = ensureClientApp(adminClient, controlSession, request, steps);

            String agentId = defaultIfBlank(firstText(request.get("agentId")), properties.agentId());
            String upstreamUserId = defaultIfBlank(firstText(request.get("upstreamUserId")), properties.upstreamUserId());
            String modelConfigId = defaultIfBlank(firstText(request.get("modelConfigId")), properties.modelConfigId());
            String upstreamUserToken = defaultIfBlank(firstText(request.get("upstreamUserToken")), session.token());

            ensureSkillGrant(adminClient, clientApp, agentId, steps);
            ensureUpstreamUserGrant(adminClient, clientApp, upstreamUserId, upstreamUserToken, steps);
            ensureModelConfigGrant(adminClient, clientApp, modelConfigId, steps);

            IssuedCredentialDTO runtimeCredential = issueRuntimeCredential(adminClient, clientApp, steps);
            if (ObserverBffProperties.isBlank(runtimeCredential.getAppKey())
                    || ObserverBffProperties.isBlank(runtimeCredential.getSecret())) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Navigator returned empty runtime credential");
            }

            revokeTemporaryApiKey(navigatorBaseUrl, session.token(), controlSession, steps);
            revokeAttempted = true;

            return new ObserverRuntimeCredential(
                    "navi-login-runtime",
                    controlSession.temporaryApiKeyId() == null ? "navi-login" : "navi-login-super-admin-proxy",
                    navigatorBaseUrl,
                    clientApp.getClientAppId(),
                    clientApp.getName(),
                    runtimeCredential.getAppKey(),
                    runtimeCredential.getSecret(),
                    null,
                    upstreamUserId,
                    agentId,
                    modelConfigId,
                    controlSession.displayUsername(),
                    controlSession.userId(),
                    controlSession.tenantId(),
                    Instant.now(),
                    steps);
        } finally {
            if (!revokeAttempted) {
                revokeTemporaryApiKey(navigatorBaseUrl, session.token(), controlSession, steps);
            }
        }
    }

    private LoginSession login(String navigatorBaseUrl, String username, String password) {
        HttpResponse<String> response;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", username);
            body.put("password", password);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(navigatorBaseUrl + "/api/v1/auth/login"))
                    .timeout(properties.sdkTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Navigator login request was interrupted", exception);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Navigator login request failed at " + navigatorBaseUrl + ": " + exception.getMessage(),
                    exception);
        }

        if (response.statusCode() >= 400) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(response.statusCode()),
                    "Navigator login failed at " + navigatorBaseUrl + ": HTTP " + response.statusCode()
                            + ", body starts with: " + safeSnippet(response.body()));
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (root.isObject() && root.has("code")) {
                int code = root.path("code").asInt(-1);
                if (code != 0 && code != 200) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Navigator login failed: " + root.path("msg").asText("unknown error"));
                }
            }

            JsonNode data = root.path("data");
            String token = text(data.path("token"));
            JsonNode user = data.path("user");
            String userId = text(user.path("id"));
            String tenantId = text(user.path("tenantId"));
            String roles = text(user.path("roles"));
            String resolvedUsername = defaultIfBlank(text(user.path("username")), username);
            if (ObserverBffProperties.isBlank(token) || ObserverBffProperties.isBlank(userId)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Navigator login response is missing token or user id");
            }
            return new LoginSession(token, userId, tenantId, resolvedUsername, roles);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            String contentType = response.headers().firstValue("content-type").orElse("unknown");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Navigator login response is not JSON from " + navigatorBaseUrl
                            + "; status=" + response.statusCode()
                            + ", content-type=" + contentType
                            + ", body starts with: " + safeSnippet(response.body()),
                    exception);
        }
    }

    private ControlSession resolveControlSession(
            String navigatorBaseUrl,
            LoginSession session,
            Map<String, Object> request,
            List<String> steps) {
        if (!ObserverBffProperties.isBlank(session.tenantId())) {
            steps.add("login-tenant:" + session.tenantId());
            return new ControlSession(
                    session.token(),
                    null,
                    null,
                    session.userId(),
                    session.tenantId(),
                    session.username());
        }

        if (!session.isSuperAdmin()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Navi login user " + session.username()
                            + " has no tenantId. Use a TENANT_ADMIN account for the target tenant.");
        }

        String targetTenantId = defaultIfBlank(firstText(request.get("targetTenantId")),
                firstText(request.get("tenantId")));
        List<UserAccount> users = listUsers(navigatorBaseUrl, session.token());
        UserAccount tenantAdmin = selectTenantAdmin(users, targetTenantId);
        ApiKeySession apiKey = createTemporaryApiKey(navigatorBaseUrl, session.token(), tenantAdmin);

        steps.add("root-target-tenant:" + tenantAdmin.tenantId());
        steps.add("temporary-tenant-admin:" + tenantAdmin.username());
        steps.add("issue-temporary-api-key:" + apiKey.id());
        return new ControlSession(
                null,
                apiKey.apiKey(),
                apiKey.id(),
                tenantAdmin.id(),
                tenantAdmin.tenantId(),
                session.username() + " -> " + tenantAdmin.username());
    }

    private NavigatorClient buildControlClient(String navigatorBaseUrl, ControlSession session) {
        NavigatorClient.Builder builder = NavigatorClient.builder()
                .baseUrl(navigatorBaseUrl)
                .timeout(properties.sdkTimeout());
        if (!ObserverBffProperties.isBlank(session.apiKey())) {
            return builder.apiKey(session.apiKey()).build();
        }
        return builder.bearerToken(session.bearerToken()).build();
    }

    private List<UserAccount> listUsers(String navigatorBaseUrl, String bearerToken) {
        JsonNode data = sendNavigatorJson(
                "GET",
                navigatorBaseUrl + "/api/v1/users",
                bearerToken,
                null,
                null);
        if (!data.isArray()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Navigator users response is not an array");
        }

        List<UserAccount> users = new ArrayList<>();
        for (JsonNode node : data) {
            users.add(new UserAccount(
                    text(node.path("id")),
                    text(node.path("username")),
                    text(node.path("tenantId")),
                    text(node.path("roles")),
                    text(node.path("status"))));
        }
        return users;
    }

    private UserAccount selectTenantAdmin(List<UserAccount> users, String targetTenantId) {
        List<UserAccount> tenantAdmins = users.stream()
                .filter(UserAccount::isActiveTenantAdmin)
                .sorted(Comparator.comparing(UserAccount::tenantId).thenComparing(UserAccount::username))
                .toList();
        if (tenantAdmins.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "root login succeeded but no active TENANT_ADMIN user was found");
        }

        if (!ObserverBffProperties.isBlank(targetTenantId)) {
            return tenantAdmins.stream()
                    .filter(user -> targetTenantId.equals(user.tenantId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "targetTenantId has no active TENANT_ADMIN user: " + targetTenantId
                                    + ". Available tenants: " + tenantCandidates(tenantAdmins)));
        }

        Set<String> tenants = new LinkedHashSet<>();
        tenantAdmins.forEach(user -> tenants.add(user.tenantId()));
        if (tenants.size() == 1) {
            return tenantAdmins.get(0);
        }

        Optional<UserAccount> localTmsAdmin = tenantAdmins.stream()
                .filter(user -> "tms-admin".equals(user.username()))
                .findFirst();
        if (localTmsAdmin.isPresent()) {
            return localTmsAdmin.get();
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "root login has no tenantId. Add targetTenantId to the request. Available tenants: "
                        + tenantCandidates(tenantAdmins));
    }

    private String tenantCandidates(List<UserAccount> tenantAdmins) {
        return tenantAdmins.stream()
                .map(user -> user.tenantId() + "(" + user.username() + ")")
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("<none>");
    }

    private ApiKeySession createTemporaryApiKey(String navigatorBaseUrl, String rootToken, UserAccount tenantAdmin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Navigator Chat Observer Debug BFF");
        body.put("expiresAt", LocalDateTime.now().plusHours(2));

        JsonNode data = sendNavigatorJson(
                "POST",
                navigatorBaseUrl + "/api/v1/users/" + encodePath(tenantAdmin.id()) + "/api-keys",
                rootToken,
                null,
                body);
        String id = text(data.path("id"));
        String apiKey = text(data.path("apiKey"));
        String maskedApiKey = text(data.path("maskedApiKey"));
        if (ObserverBffProperties.isBlank(id) || ObserverBffProperties.isBlank(apiKey)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Navigator returned empty temporary API key for tenant admin " + tenantAdmin.username());
        }
        return new ApiKeySession(id, apiKey, maskedApiKey);
    }

    private void revokeTemporaryApiKey(
            String navigatorBaseUrl,
            String rootToken,
            ControlSession controlSession,
            List<String> steps) {
        if (controlSession == null || ObserverBffProperties.isBlank(controlSession.temporaryApiKeyId())) {
            return;
        }
        try {
            sendNavigatorJson(
                    "DELETE",
                    navigatorBaseUrl + "/api/v1/users/api-keys/" + encodePath(controlSession.temporaryApiKeyId()),
                    rootToken,
                    null,
                    null);
            steps.add("revoke-temporary-api-key:" + controlSession.temporaryApiKeyId());
        } catch (Exception exception) {
            steps.add("revoke-temporary-api-key-failed:" + controlSession.temporaryApiKeyId());
        }
    }

    private JsonNode sendNavigatorJson(
            String method,
            String uri,
            String bearerToken,
            String apiKey,
            Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .timeout(properties.sdkTimeout())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            if (!ObserverBffProperties.isBlank(bearerToken)) {
                builder.header("Authorization", bearerToken.startsWith("Bearer ")
                        ? bearerToken
                        : "Bearer " + bearerToken);
            }
            if (!ObserverBffProperties.isBlank(apiKey)) {
                builder.header("X-API-Key", apiKey);
            }

            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(response.statusCode()),
                        "Navigator request failed: " + method + " " + uri
                                + " HTTP " + response.statusCode()
                                + ", body starts with: " + safeSnippet(response.body()));
            }
            return parseNavigatorData(response, method, uri);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Navigator request was interrupted: " + method + " " + uri, exception);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Navigator request failed: " + method + " " + uri + ": " + exception.getMessage(), exception);
        }
    }

    private JsonNode parseNavigatorData(HttpResponse<String> response, String method, String uri) {
        if (response.body() == null || response.body().isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (root.isObject() && root.has("code")) {
                int code = root.path("code").asInt(-1);
                if (code != 0 && code != 200) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Navigator request failed: " + method + " " + uri
                                    + ": " + root.path("msg").asText("unknown error"));
                }
                JsonNode data = root.path("data");
                return data.isMissingNode() ? objectMapper.nullNode() : data;
            }
            return root;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            String contentType = response.headers().firstValue("content-type").orElse("unknown");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Navigator response is not JSON: " + method + " " + uri
                            + "; status=" + response.statusCode()
                            + ", content-type=" + contentType
                            + ", body starts with: " + safeSnippet(response.body()),
                    exception);
        }
    }

    private ClientAppDTO ensureClientApp(
            NavigatorClient adminClient,
            ControlSession session,
            Map<String, Object> request,
            List<String> steps) {
        String requestedClientAppId = defaultIfBlank(firstText(request.get("clientAppId")), properties.clientAppId());
        String requestedClientAppName = defaultIfBlank(firstText(request.get("clientAppName")),
                properties.debugClientAppName());
        String capabilityDomain = defaultIfBlank(firstText(request.get("capabilityDomain")),
                properties.debugCapabilityDomain());

        List<ClientAppDTO> apps = adminClient.businessAgent().listClientApps();
        if (!ObserverBffProperties.isBlank(requestedClientAppId)) {
            ClientAppDTO app = apps.stream()
                    .filter(item -> requestedClientAppId.equals(item.getClientAppId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "client app not found: " + requestedClientAppId));
            requireActiveClientApp(app);
            steps.add("reuse-client-app:" + app.getClientAppId());
            return app;
        }

        Optional<ClientAppDTO> existing = apps.stream()
                .filter(item -> requestedClientAppName.equals(item.getName()))
                .filter(item -> ACTIVE.equals(item.getStatus()))
                .findFirst();
        if (existing.isPresent()) {
            ClientAppDTO app = existing.get();
            steps.add("reuse-client-app:" + app.getClientAppId());
            return app;
        }

        IssueProvisioningCredentialForm provisioningForm = new IssueProvisioningCredentialForm();
        provisioningForm.setTargetTenantId(session.tenantId());
        provisioningForm.setMaxUses(1);
        provisioningForm.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        provisioningForm.setOwnerUserId(session.userId());
        provisioningForm.setCapabilityDomain(capabilityDomain);
        provisioningForm.setAuditTag("navigator-chat-observer");
        IssuedCredentialDTO provisioning = adminClient.businessAgent().issueProvisioningCredential(provisioningForm);
        if (provisioning == null || ObserverBffProperties.isBlank(provisioning.getToken())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Navigator returned empty provisioning credential");
        }
        steps.add("issue-provisioning-credential");

        CreateClientAppForm createForm = new CreateClientAppForm();
        createForm.setProvisioningToken(provisioning.getToken());
        createForm.setName(requestedClientAppName);
        createForm.setDescription("Local debug ClientApp for Navigator Chat Observer BFF");
        createForm.setOwnerUserId(session.userId());
        createForm.setCapabilityDomain(capabilityDomain);
        ClientAppDTO created = adminClient.businessAgent().createClientApp(createForm);
        if (created == null || ObserverBffProperties.isBlank(created.getClientAppId())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Navigator returned empty ClientApp");
        }
        steps.add("create-client-app:" + created.getClientAppId());
        return created;
    }

    private IssuedCredentialDTO issueRuntimeCredential(
            NavigatorClient adminClient,
            ClientAppDTO clientApp,
            List<String> steps) {
        IssueRuntimeCredentialForm form = new IssueRuntimeCredentialForm();
        form.setDescription("Navigator Chat Observer Debug BFF runtime credential");
        form.setExpiresAt(LocalDateTime.now().plusDays(1));
        IssuedCredentialDTO credential = adminClient.businessAgent()
                .issueRuntimeCredential(clientApp.getClientAppId(), form);
        steps.add("issue-runtime-credential");
        return credential;
    }

    private void ensureSkillGrant(
            NavigatorClient adminClient,
            ClientAppDTO clientApp,
            String agentId,
            List<String> steps) {
        if (ObserverBffProperties.isBlank(agentId)) {
            steps.add("skip-skill-grant:no-agent-id");
            return;
        }
        GrantSkillToClientAppForm form = new GrantSkillToClientAppForm();
        form.setSkillId(agentId);
        form.setStatus(ENABLED);
        try {
            adminClient.businessAgent().grantSkillToClientApp(clientApp.getClientAppId(), form);
        } catch (NavigatorApiException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("Skill not found")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Navigator login auth succeeded, but Agent ID '" + agentId
                                + "' is not a registered Business Agent Skill in the target tenant. "
                                + "Use a real OpenAPI agent/skill id, or sync/materialize the BizWorker skill bundle first. "
                                + "Original error: " + exception.getMessage(),
                        exception);
            }
            throw exception;
        }
        steps.add("grant-skill:" + agentId);
    }

    private void ensureUpstreamUserGrant(
            NavigatorClient adminClient,
            ClientAppDTO clientApp,
            String upstreamUserId,
            String upstreamUserToken,
            List<String> steps) {
        if (ObserverBffProperties.isBlank(upstreamUserId)) {
            steps.add("skip-upstream-user:no-upstream-user-id");
            return;
        }
        GrantUpstreamUserForm form = new GrantUpstreamUserForm();
        form.setUpstreamUserId(upstreamUserId);
        form.setUpstreamUserToken(upstreamUserToken);
        form.setStatus(ENABLED);
        adminClient.businessAgent().grantUpstreamUserAccess(clientApp.getClientAppId(), form);
        steps.add("grant-upstream-user:" + upstreamUserId);
    }

    private void ensureModelConfigGrant(
            NavigatorClient adminClient,
            ClientAppDTO clientApp,
            String modelConfigId,
            List<String> steps) {
        if (ObserverBffProperties.isBlank(modelConfigId)) {
            steps.add("skip-model-config:no-model-config-id");
            return;
        }

        List<ClientAppModelConfigGrantDTO> grants = adminClient.businessAgent()
                .listModelConfigGrants(clientApp.getClientAppId());
        Optional<ClientAppModelConfigGrantDTO> existing = grants.stream()
                .filter(item -> modelConfigId.equals(item.getModelConfigId()))
                .findFirst();
        if (existing.isPresent()) {
            ClientAppModelConfigGrantDTO grant = existing.get();
            if (!ENABLED.equals(grant.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "model config grant exists but is not enabled: " + modelConfigId);
            }
            if (!Boolean.TRUE.equals(grant.getIsDefault())) {
                adminClient.businessAgent().setDefaultModelConfigGrant(clientApp.getClientAppId(), grant.getId());
                steps.add("set-default-model-config:" + modelConfigId);
            } else {
                steps.add("reuse-model-config:" + modelConfigId);
            }
            return;
        }

        GrantModelConfigForm form = new GrantModelConfigForm();
        form.setModelConfigId(modelConfigId);
        form.setIsDefault(true);
        form.setGrantScope("APP");
        adminClient.businessAgent().grantModelConfig(clientApp.getClientAppId(), form);
        steps.add("grant-model-config:" + modelConfigId);
    }

    private void requireActiveClientApp(ClientAppDTO app) {
        if (!ACTIVE.equals(app.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "client app is not active: " + app.getClientAppId());
        }
    }

    private String firstText(Object value) {
        return text(value);
    }

    private String defaultIfBlank(String value, String fallback) {
        return ObserverBffProperties.isBlank(value) ? fallback : value;
    }

    private String normalizeBaseUrl(String value) {
        String text = defaultIfBlank(value, "http://127.0.0.1:8112").trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String safeSnippet(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String masked = body
                .replaceAll("(?i)(\"(?:token|accessToken|apiKey|appSecret|secret|password)\"\\s*:\\s*\")[^\"]+(\")", "$1***$2")
                .replaceAll("\\s+", " ")
                .trim();
        return masked.length() > 220 ? masked.substring(0, 220) : masked;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode node) {
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            String text = node.asText();
            return text == null || text.isBlank() ? null : text.trim();
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private record LoginSession(String token, String userId, String tenantId, String username, String roles) {
        boolean isSuperAdmin() {
            return roles != null && roles.contains("SUPER_ADMIN");
        }
    }

    private record ControlSession(
            String bearerToken,
            String apiKey,
            String temporaryApiKeyId,
            String userId,
            String tenantId,
            String displayUsername) {
    }

    private record UserAccount(String id, String username, String tenantId, String roles, String status) {
        boolean isActiveTenantAdmin() {
            return !ObserverBffProperties.isBlank(id)
                    && !ObserverBffProperties.isBlank(username)
                    && !ObserverBffProperties.isBlank(tenantId)
                    && roles != null
                    && roles.contains("TENANT_ADMIN")
                    && ACTIVE.equals(status);
        }
    }

    private record ApiKeySession(String id, String apiKey, String maskedApiKey) {
    }
}
