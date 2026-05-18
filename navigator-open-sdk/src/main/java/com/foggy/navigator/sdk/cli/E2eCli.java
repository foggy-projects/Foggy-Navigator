package com.foggy.navigator.sdk.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.foggy.navigator.sdk.api.BusinessAgentApi;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.businessagent.E2eModelConfigEnsureResultDTO;
import com.foggy.navigator.sdk.model.businessagent.EnsureE2eModelConfigForm;

import java.io.PrintStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class E2eCli {
    private final PrintStream out;
    private final PrintStream err;
    private final Path cwd;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private UpstreamCliConfig config;

    public E2eCli(PrintStream out, PrintStream err, Path cwd) {
        this.out = out;
        this.err = err;
        this.cwd = cwd;
    }

    public static void main(String[] args) {
        int code = new E2eCli(System.out, System.err, Path.of("").toAbsolutePath())
                .run(args, System.getenv());
        if (code != 0) {
            System.exit(code);
        }
    }

    public int run(String[] args, Map<String, String> env) {
        CliArguments parsed = CliArguments.parse(args);
        try {
            config = UpstreamCliConfig.load(parsed, env, cwd);
            return dispatch(parsed);
        } catch (UpstreamCliException e) {
            err.println("ERROR: " + SecretMasker.redactKnownSecrets(e.getMessage(),
                    config != null ? config.sensitiveValues() : List.of()));
            return 2;
        } catch (Exception e) {
            err.println("ERROR: " + SecretMasker.redactKnownSecrets(e.getMessage(),
                    config != null ? config.sensitiveValues() : List.of()));
            return 1;
        }
    }

    private int dispatch(CliArguments args) throws Exception {
        return switch (args.command()) {
            case "config check" -> configCheck();
            case "script register" -> scriptRegister(args);
            case "script cleanup" -> scriptCleanup(args);
            case "debug requests" -> debugRequests(args);
            case "model ensure" -> modelEnsure(args);
            case "", "help" -> usage();
            default -> throw new UpstreamCliException("Unknown command: " + args.command());
        };
    }

    private int usage() {
        out.println("Usage: navi-e2e <command> [options]");
        out.println("Commands: config check, script register, script cleanup, debug requests, model ensure");
        out.println("Mock LLM options: --mock-url <url>, or NAVI_E2E_MOCK_LLM_URL in .navigator/upstream.env");
        return 0;
    }

    private int configCheck() {
        out.println("Navigator E2E CLI config check");
        out.println("profile=" + (config.profilePath() == null ? "(none)" : config.profilePath()));
        out.println("profileExists=" + config.profileExists());
        out.println("profileGitIgnored=" + config.profileIsGitIgnored());
        out.println("NAVI_E2E_MOCK_LLM_URL=" + valueOrEmpty(mockUrl()));
        if (!config.profileIsGitIgnored()) {
            throw new UpstreamCliException("Profile path is not git-ignored: " + config.profilePath());
        }
        return 0;
    }

    private int scriptRegister(CliArguments args) throws Exception {
        Path file = resolveRequiredPath(args.option("file"), "script file");
        JsonNode script = objectMapper.readTree(file.toFile());
        String body = objectMapper.writeValueAsString(script);
        Map<String, Object> result = execute("POST", "/__e2e/scripts", body);
        out.println("script register ok");
        out.println("traceId=" + valueOrEmpty(result.get("traceId")));
        out.println("scenarioId=" + valueOrEmpty(result.get("scenarioId")));
        out.println("turns=" + valueOrEmpty(result.get("turns")));
        out.println("expiresAt=" + valueOrEmpty(result.get("expiresAt")));
        return 0;
    }

    private int scriptCleanup(CliArguments args) throws Exception {
        String traceId = requiredOption(args, "trace-id", "trace id");
        Map<String, Object> result = execute("DELETE", "/__e2e/scripts/" + encodePath(traceId), null);
        out.println("script cleanup ok");
        out.println("traceId=" + valueOrEmpty(result.get("traceId")));
        out.println("removed=" + valueOrEmpty(result.get("removed")));
        return 0;
    }

    private int debugRequests(CliArguments args) throws Exception {
        String traceId = requiredOption(args, "trace-id", "trace id");
        Object result = executeAny("GET", "/__debug/requests?traceId=" + encodeQuery(traceId), null);
        out.println(objectMapper.writeValueAsString(result));
        return 0;
    }

    private int modelEnsure(CliArguments args) {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        EnsureE2eModelConfigForm form = new EnsureE2eModelConfigForm();
        form.setStandard(optionOrDefault(args, "standard", "biz-worker"));
        form.setMockBaseUrl(mockUrl());
        form.setSetDefault(args.flag("set-default"));

        E2eModelConfigEnsureResultDTO result = businessAgentControlApi().ensureE2eModelConfig(clientAppId, form);
        if (result == null || !hasText(result.getModelConfigId())) {
            throw new UpstreamCliException("model ensure response did not include modelConfigId");
        }
        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_MODEL_CONFIG_ID", result.getModelConfigId());
        }
        out.println("e2e model ensure ok");
        out.println("clientAppId=" + valueOrEmpty(result.getClientAppId()));
        out.println("standard=" + valueOrEmpty(result.getStandard()));
        out.println("mockBaseUrl=" + valueOrEmpty(result.getMockBaseUrl()));
        out.println("modelConfigId=" + valueOrEmpty(result.getModelConfigId()));
        out.println("modelConfigName=" + valueOrEmpty(result.getModelConfigName()));
        out.println("modelCreated=" + result.isModelCreated());
        out.println("modelUpdated=" + result.isModelUpdated());
        out.println("grantId=" + valueOrEmpty(result.getGrantId()));
        out.println("grantCreated=" + result.isGrantCreated());
        out.println("grantStatus=" + valueOrEmpty(result.getGrantStatus()));
        out.println("isDefault=" + valueOrEmpty(result.getIsDefault()));
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_MODEL_CONFIG_ID");
        }
        return 0;
    }

    private Map<String, Object> execute(String method, String path, String body) throws Exception {
        Object result = executeAny(method, path, body);
        if (!(result instanceof Map<?, ?> map)) {
            throw new UpstreamCliException("Expected JSON object response from Mock LLM");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }

    private Object executeAny(String method, String path, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(trimTrailingSlash(mockUrl()) + path)
                .toURL()
                .openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = connection.getOutputStream()) {
                os.write(bytes);
            }
        }

        int status = connection.getResponseCode();
        String responseBody = readResponseBody(connection, status);
        if (status >= 400) {
            throw new UpstreamCliException("Mock LLM request failed: HTTP " + status
                    + " " + responseBody);
        }
        if (responseBody == null || responseBody.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(responseBody, new TypeReference<Object>() {});
    }

    private String readResponseBody(HttpURLConnection connection, int status) throws Exception {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path resolveRequiredPath(String path, String description) {
        if (!hasText(path)) {
            throw new UpstreamCliException(description + " is required (--file)");
        }
        Path resolved = Path.of(path);
        if (!resolved.isAbsolute()) {
            resolved = cwd.resolve(resolved);
        }
        if (!Files.exists(resolved)) {
            throw new UpstreamCliException(description + " not found: " + resolved);
        }
        return resolved.normalize();
    }

    private String mockUrl() {
        return config.get("NAVI_E2E_MOCK_LLM_URL");
    }

    private BusinessAgentApi businessAgentControlApi() {
        String controlApiKey = config.get("NAVI_CONTROL_API_KEY");
        String adminToken = config.get("NAVI_ADMIN_TOKEN");
        if (!hasText(controlApiKey) && !hasText(adminToken)) {
            throw new UpstreamCliException("control-plane credential is required (NAVI_CONTROL_API_KEY; admin fallback: NAVI_ADMIN_TOKEN)");
        }
        return new BusinessAgentApi(new HttpHelper(
                config.required("NAVI_BASE_URL", "Navigator base URL"),
                null,
                adminToken,
                config.get("NAVI_TENANT_ID"),
                controlApiKey,
                Duration.ofSeconds(30)));
    }

    private String requiredOptionOrConfig(CliArguments args, String option, String key, String description) {
        String value = args.option(option);
        if (hasText(value)) {
            return value;
        }
        return config.required(key, description);
    }

    private String optionOrDefault(CliArguments args, String option, String defaultValue) {
        String value = args.option(option);
        return hasText(value) ? value : defaultValue;
    }

    private static String requiredOption(CliArguments args, String name, String description) {
        String value = args.option(name);
        if (!hasText(value)) {
            throw new UpstreamCliException(description + " is required (--" + name + ")");
        }
        return value;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new UpstreamCliException("Mock LLM URL is required (NAVI_E2E_MOCK_LLM_URL)");
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String encodePath(String value) {
        return encodeQuery(value).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String valueOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
