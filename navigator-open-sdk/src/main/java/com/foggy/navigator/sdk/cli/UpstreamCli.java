package com.foggy.navigator.sdk.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.sdk.NavigatorClient;
import com.foggy.navigator.sdk.api.AgentApi;
import com.foggy.navigator.sdk.api.BusinessAgentApi;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.AgentTask;
import com.foggy.navigator.sdk.model.AgentReadiness;
import com.foggy.navigator.sdk.model.AgentReadinessCheck;
import com.foggy.navigator.sdk.model.SessionListPage;
import com.foggy.navigator.sdk.model.SessionMessage;
import com.foggy.navigator.sdk.model.SessionMessagesPage;
import com.foggy.navigator.sdk.model.SessionSummary;
import com.foggy.navigator.sdk.model.SkillArtifactFile;
import com.foggy.navigator.sdk.model.SkillArtifactSlice;
import com.foggy.navigator.sdk.model.SkillArtifactTree;
import com.foggy.navigator.sdk.model.TaskMessagesPage;
import com.foggy.navigator.sdk.model.businessagent.ClientAppRuntimeAccessTokenDTO;
import com.foggy.navigator.sdk.model.businessagent.ClientAppUpstreamUserGrantDTO;
import com.foggy.navigator.sdk.model.businessagent.GrantUpstreamUserForm;
import com.foggy.navigator.sdk.model.businessagent.SkillBundleDTO;
import com.foggy.navigator.sdk.model.businessagent.SyncAccountSkillBundleForm;
import com.foggy.navigator.sdk.model.businessagent.SyncSkillBundleForm;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class UpstreamCli {
    private final PrintStream out;
    private final PrintStream err;
    private final Path cwd;
    private UpstreamCliConfig config;
    private String resolvedClientAppAccessToken;

    public UpstreamCli(PrintStream out, PrintStream err, Path cwd) {
        this.out = out;
        this.err = err;
        this.cwd = cwd;
    }

    public static void main(String[] args) {
        int code = new UpstreamCli(System.out, System.err, Path.of("").toAbsolutePath())
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
            case "runtime-token" -> runtimeToken(args);
            case "verify-agent-readiness", "verify-agent-grant" -> verifyAgentReadiness(args);
            case "ensure-grant" -> ensureGrant(args);
            case "ask" -> ask(args);
            case "messages" -> messages(args);
            case "sessions" -> sessions(args);
            case "session-messages" -> sessionMessages(args);
            case "skill tree" -> skillTree(args);
            case "skill read" -> skillRead(args);
            case "skill sync" -> skillSync(args);
            case "tms token issue-staff", "tms order create-self-pickup-sign-ready",
                    "tms order readiness" -> unsupportedTmsHelper();
            case "", "help" -> usage();
            default -> throw new UpstreamCliException("Unknown command: " + args.command());
        };
    }

    private int usage() {
        out.println("Usage: navi upstream <command> [options]");
        out.println("Commands: config check, runtime-token, verify-agent-readiness, verify-agent-grant, ensure-grant, ask, messages, sessions, session-messages, skill tree, skill read, skill sync");
        return 0;
    }

    private int configCheck() {
        out.println("Navigator upstream CLI config check");
        out.println("profile=" + (config.profilePath() == null ? "(none)" : config.profilePath()));
        out.println("profileExists=" + config.profileExists());
        out.println("profileGitIgnored=" + config.profileIsGitIgnored());
        if (!config.profileIsGitIgnored()) {
            throw new UpstreamCliException("Profile path is not git-ignored: " + config.profilePath());
        }
        for (Map.Entry<String, String> entry : config.values().entrySet()) {
            if (isSensitiveKey(entry.getKey())) {
                out.println(entry.getKey() + "=" + SecretMasker.mask(entry.getValue()));
            } else {
                out.println(entry.getKey() + "=" + valueOrEmpty(entry.getValue()));
            }
        }
        return 0;
    }

    private int runtimeToken(CliArguments args) {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        ClientAppRuntimeAccessTokenDTO token = exchangeRuntimeAccessToken(args);
        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_CLIENT_APP_ACCESS_TOKEN", token.getAccessToken());
        }
        out.println("runtime-token ok");
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_CLIENT_APP_ACCESS_TOKEN");
        }
        out.println("clientAppId=" + valueOrEmpty(token.getClientAppId()));
        out.println("appKey=" + SecretMasker.mask(token.getAppKey()));
        out.println("accessToken=" + SecretMasker.mask(token.getAccessToken()));
        out.println("expiresInSeconds=" + valueOrEmpty(token.getExpiresInSeconds()));
        out.println("expiresAt=" + valueOrEmpty(token.getExpiresAt()));
        return 0;
    }

    private int ensureGrant(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String upstreamUserId = requiredOption(args, "upstream-user-id", "upstream user id");
        String upstreamUserToken = config.required("TMS_STAFF_SESSION_TOKEN", "upstream user token");
        String adminToken = config.get("NAVI_ADMIN_TOKEN");
        String adminApiKey = config.get("NAVI_ADMIN_API_KEY");
        if (!hasText(adminToken) && !hasText(adminApiKey)) {
            throw new UpstreamCliException("admin/provisioning credential is required (NAVI_ADMIN_TOKEN or NAVI_ADMIN_API_KEY)");
        }

        GrantUpstreamUserForm form = new GrantUpstreamUserForm();
        form.setUpstreamUserId(upstreamUserId);
        form.setUpstreamUserToken(upstreamUserToken);
        form.setStatus("ENABLED");

        NavigatorClient.Builder builder = NavigatorClient.builder()
                .baseUrl(config.required("NAVI_BASE_URL", "Navigator base URL"))
                .tenantId(config.required("NAVI_TENANT_ID", "tenant id"))
                .timeout(Duration.ofSeconds(30));
        if (hasText(adminToken)) {
            builder.adminToken(adminToken);
        } else {
            builder.apiKey(adminApiKey);
        }
        ClientAppUpstreamUserGrantDTO grant = builder.build()
                .businessAgent()
                .grantUpstreamUserAccess(clientAppId, form);
        out.println("ensure-grant ok");
        out.println("clientAppId=" + valueOrEmpty(grant.getClientAppId()));
        out.println("upstreamUserId=" + valueOrEmpty(grant.getUpstreamUserId()));
        out.println("status=" + valueOrEmpty(grant.getStatus()));
        return 0;
    }

    private int verifyAgentReadiness(CliArguments args) {
        String agent = agentCode(args);
        String upstreamUserId = requiredOption(args, "upstream-user-id", "upstream user id");
        AgentReadiness readiness = agentApi().verifyReadinessWithClientAppAccessToken(
                agent,
                upstreamUserId,
                modelConfigId(args),
                clientAppKey(args),
                clientAppAccessToken(args));
        out.println("verify-agent-readiness " + valueOrEmpty(readiness.getOverallStatus()));
        out.println("baseUrl=" + valueOrEmpty(readiness.getBaseUrl()));
        out.println("clientAppId=" + valueOrEmpty(readiness.getClientAppId()));
        out.println("clientAppName=" + redact(readiness.getClientAppName()));
        out.println("agentCode=" + valueOrEmpty(readiness.getAgentCode()));
        out.println("upstreamUserId=" + valueOrEmpty(readiness.getUpstreamUserId()));
        out.println("requestedModelConfigId=" + valueOrEmpty(readiness.getRequestedModelConfigId()));
        out.println("effectiveModelConfigId=" + valueOrEmpty(readiness.getEffectiveModelConfigId()));
        if (readiness.getChecks() != null) {
            for (AgentReadinessCheck check : readiness.getChecks()) {
                out.println("check " + valueOrEmpty(check.getCode())
                        + "=" + valueOrEmpty(check.getStatus())
                        + (hasText(check.getMessage()) ? " message=" + redact(check.getMessage()) : ""));
            }
        }
        if (readiness.getSkillArtifact() != null && readiness.getSkillArtifact().isAvailable()) {
            out.println("skillArtifactTreeUrl=" + valueOrEmpty(readiness.getSkillArtifact().getTreeUrl()));
        }
        return "OK".equals(readiness.getOverallStatus()) ? 0 : 2;
    }

    private int ask(CliArguments args) {
        String agent = agentCode(args);
        String upstreamUserId = requiredOption(args, "upstream-user-id", "upstream user id");
        String message = requiredOption(args, "message", "message");
        Map<String, Object> clientContext = parseClientContext(args);
        AgentTask task = agentApi().askWithClientAppAccessToken(
                agent,
                message,
                args.option("context-id"),
                parseInteger(args.option("max-turns")),
                clientContext,
                clientAppKey(args),
                clientAppAccessToken(args),
                upstreamUserId);
        printTask(task);
        return 0;
    }

    private int messages(CliArguments args) throws InterruptedException {
        String agent = agentCode(args);
        String taskId = requiredOption(args, "task-id", "task id");
        String upstreamUserId = args.option("upstream-user-id");
        int limit = parseInteger(args.option("limit"), 50);
        String cursor = args.option("cursor");
        int timeoutSeconds = parseInteger(args.option("timeout-seconds"), 600);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        AgentApi api = agentApi();
        do {
            TaskMessagesPage page = api.getTaskMessagesWithClientAppAccessToken(
                    agent, taskId, limit, cursor, clientAppKey(args), clientAppAccessToken(args), upstreamUserId);
            printMessages(page.getMessages());
            cursor = page.getNextCursor();
            AgentTask task = api.getTaskWithClientAppAccessToken(
                    agent, taskId, clientAppKey(args), clientAppAccessToken(args), upstreamUserId);
            out.println("taskStatus=" + valueOrEmpty(task.getStatus()));
            if (task.isTerminal() || !args.flag("poll")) {
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new UpstreamCliException("messages polling timed out for task " + taskId);
            }
            Thread.sleep(config.pollIntervalSeconds() * 1000L);
        } while (true);
        return 0;
    }

    private int sessions(CliArguments args) {
        String agent = agentCode(args);
        SessionListPage page = agentApi().listSessionsWithClientAppAccessToken(
                agent, parseInteger(args.option("limit"), 20), args.option("cursor"),
                clientAppKey(args), clientAppAccessToken(args), args.option("upstream-user-id"));
        if (page.getSessions() != null) {
            for (SessionSummary session : page.getSessions()) {
                out.println("session contextId=" + valueOrEmpty(session.getContextId())
                        + " status=" + valueOrEmpty(session.getStatus())
                        + " latestTaskId=" + valueOrEmpty(session.getLatestTaskId())
                        + " title=" + redact(session.getTitle()));
            }
        }
        out.println("hasMore=" + page.isHasMore());
        out.println("nextCursor=" + valueOrEmpty(page.getNextCursor()));
        return 0;
    }

    private int sessionMessages(CliArguments args) {
        String agent = agentCode(args);
        String contextId = requiredOption(args, "context-id", "context id");
        SessionMessagesPage page = agentApi().getSessionMessagesWithClientAppAccessToken(
                agent, contextId, parseInteger(args.option("limit"), 50), args.option("cursor"),
                clientAppKey(args), clientAppAccessToken(args), args.option("upstream-user-id"));
        printMessages(page.getMessages());
        out.println("nextCursor=" + valueOrEmpty(page.getNextCursor()));
        return 0;
    }

    private int skillTree(CliArguments args) {
        String agent = agentCode(args);
        SkillArtifactTree tree = agentApi().getSkillArtifactTreeWithClientAppAccessToken(
                agent, clientAppKey(args), clientAppAccessToken(args));
        out.println("skillId=" + valueOrEmpty(tree.getSkillId()));
        out.println("artifactVersion=" + valueOrEmpty(tree.getArtifactVersion()));
        if (tree.getFiles() != null) {
            for (SkillArtifactFile file : tree.getFiles()) {
                out.println("file path=" + valueOrEmpty(file.getPath())
                        + " type=" + valueOrEmpty(file.getType())
                        + " size=" + file.getSize()
                        + " lineCount=" + file.getLineCount()
                        + " sliceUrl=" + valueOrEmpty(file.getSliceUrl()));
            }
        }
        return 0;
    }

    private int skillRead(CliArguments args) {
        String agent = agentCode(args);
        String path = requiredOption(args, "path", "path");
        int startLine = parseInteger(args.option("start-line"), 1);
        int startColumn = parseInteger(args.option("start-column"), 1);
        int maxChars = parseInteger(args.option("max-chars"), 8000);
        SkillArtifactSlice slice = agentApi().readSkillArtifactSliceWithClientAppAccessToken(
                agent,
                path,
                startLine,
                startColumn,
                maxChars,
                clientAppKey(args),
                clientAppAccessToken(args));
        out.println("skillId=" + valueOrEmpty(slice.getSkillId()));
        out.println("path=" + valueOrEmpty(slice.getPath()));
        out.println("range=" + slice.getStartLine() + ":" + slice.getStartColumn()
                + "-" + slice.getEndLine() + ":" + slice.getEndColumn());
        out.println("next=" + slice.getNextLine() + ":" + slice.getNextColumn());
        out.println("truncated=" + slice.isTruncated());
        out.println("content:");
        out.print(redact(slice.getContent()));
        if (!valueOrEmpty(slice.getContent()).endsWith("\n")) {
            out.println();
        }
        if (slice.isTruncated()) {
            out.println("continueCommand=upstream skill read --agent-code " + agent
                    + " --path " + path
                    + " --start-line " + slice.getNextLine()
                    + " --start-column " + slice.getNextColumn()
                    + " --max-chars " + maxChars);
        }
        return 0;
    }

    private int skillSync(CliArguments args) throws Exception {
        String scope = requiredOption(args, "scope", "scope");
        String manifest = requiredOption(args, "manifest", "manifest path");
        Path manifestPath = cwd.resolve(manifest).normalize();
        if (!Files.isRegularFile(manifestPath)) {
            throw new UpstreamCliException("manifest file not found: " + manifestPath);
        }
        String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        SkillBundleDTO dto;
        String normalizedScope = normalizeSkillBundleScope(scope);
        if ("ACCOUNT_PRIVATE".equals(normalizedScope)) {
            SyncAccountSkillBundleForm form = mapper.readValue(json, SyncAccountSkillBundleForm.class);
            String upstreamUserId = requiredOption(args, "upstream-user-id", "upstream user id");
            dto = agentApi().syncMyAccountSkillBundleWithClientAppAccessToken(
                    form,
                    clientAppKey(args),
                    clientAppAccessToken(args),
                    upstreamUserId);
        } else {
            SyncSkillBundleForm form = mapper.readValue(json, SyncSkillBundleForm.class);
            form.setScope("CLIENT_APP_PUBLIC");
            if (!hasText(form.getClientAppId())) {
                form.setClientAppId(requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id"));
            }
            dto = businessAgentControlApi().syncSkillBundle(form);
        }
        printSkillBundle(dto);
        return 0;
    }

    private int unsupportedTmsHelper() {
        throw new UpstreamCliException("TMS test-only helper is not implemented in this CLI build; use env/profile tokens without printing secrets");
    }

    private AgentApi agentApi() {
        return new AgentApi(openHttp());
    }

    private BusinessAgentApi businessAgentControlApi() {
        String adminToken = config.get("NAVI_ADMIN_TOKEN");
        String adminApiKey = config.get("NAVI_ADMIN_API_KEY");
        if (!hasText(adminToken) && !hasText(adminApiKey)) {
            throw new UpstreamCliException("admin/provisioning credential is required (NAVI_ADMIN_TOKEN or NAVI_ADMIN_API_KEY)");
        }
        return new BusinessAgentApi(new HttpHelper(
                config.required("NAVI_BASE_URL", "Navigator base URL"),
                adminApiKey,
                adminToken,
                config.get("NAVI_TENANT_ID"),
                Duration.ofSeconds(30)));
    }

    private HttpHelper openHttp() {
        return new HttpHelper(config.required("NAVI_BASE_URL", "Navigator base URL"),
                null, null, config.get("NAVI_TENANT_ID"), Duration.ofSeconds(30));
    }

    private String clientAppKey(CliArguments args) {
        return requiredOptionOrConfig(args, "client-app-key", "NAVI_CLIENT_APP_KEY", "client app key");
    }

    private String clientAppAccessToken(CliArguments args) {
        if (hasText(resolvedClientAppAccessToken)) {
            return resolvedClientAppAccessToken;
        }
        if (hasText(config.get("NAVI_CLIENT_APP_SECRET"))) {
            ClientAppRuntimeAccessTokenDTO token = exchangeRuntimeAccessToken(args);
            resolvedClientAppAccessToken = token.getAccessToken();
            if (!hasText(resolvedClientAppAccessToken)) {
                throw new UpstreamCliException("runtime token response did not include accessToken");
            }
            config.setValue("NAVI_CLIENT_APP_ACCESS_TOKEN", resolvedClientAppAccessToken);
            return resolvedClientAppAccessToken;
        }
        return config.required("NAVI_CLIENT_APP_ACCESS_TOKEN", "client app access token");
    }

    private ClientAppRuntimeAccessTokenDTO exchangeRuntimeAccessToken(CliArguments args) {
        String appKey = requiredOptionOrConfig(args, "client-app-key", "NAVI_CLIENT_APP_KEY", "client app key");
        String appSecret = config.required("NAVI_CLIENT_APP_SECRET", "client app secret");
        BusinessAgentApi api = new BusinessAgentApi(openHttp());
        ClientAppRuntimeAccessTokenDTO token = api.exchangeRuntimeAccessToken(appKey, appSecret);
        if (token == null || !hasText(token.getAccessToken())) {
            throw new UpstreamCliException("runtime token response did not include accessToken");
        }
        config.setValue("NAVI_CLIENT_APP_ACCESS_TOKEN", token.getAccessToken());
        return token;
    }

    private String modelConfigId(CliArguments args) {
        String value = args.option("model-config-id");
        if (hasText(value)) {
            return value;
        }
        return config.get("NAVI_MODEL_CONFIG_ID");
    }

    private String agentCode(CliArguments args) {
        String value = args.option("agent-code");
        if (hasText(value)) {
            return value;
        }
        return requiredOptionOrConfig(args, "agent", "NAVI_AGENT_CODE", "agent");
    }

    private Map<String, Object> parseClientContext(CliArguments args) {
        String inlineJson = args.option("client-context-json");
        String file = args.option("client-context-file");
        if (hasText(inlineJson) && hasText(file)) {
            throw new UpstreamCliException("Use only one of --client-context-json or --client-context-file");
        }
        String json = null;
        if (hasText(inlineJson)) {
            json = inlineJson;
        } else if (hasText(file)) {
            Path path = cwd.resolve(file).normalize();
            try {
                json = Files.readString(path, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new UpstreamCliException("Failed to read client context file: " + path);
            }
        }
        if (!hasText(json)) {
            return null;
        }
        try {
            Map<String, Object> parsed = new ObjectMapper().readValue(json, new TypeReference<>() {});
            return parsed != null && !parsed.isEmpty() ? parsed : null;
        } catch (Exception e) {
            throw new UpstreamCliException("clientContext must be a valid JSON object");
        }
    }

    private void printTask(AgentTask task) {
        out.println("taskId=" + valueOrEmpty(task.getTaskId()));
        out.println("status=" + valueOrEmpty(task.getStatus()));
        out.println("contextId=" + valueOrEmpty(task.getContextId()));
        if (hasText(task.getErrorMessage())) {
            out.println("errorMessage=" + redact(task.getErrorMessage()));
        }
    }

    private void printSkillBundle(SkillBundleDTO dto) {
        out.println("skill sync ok");
        out.println("scope=" + valueOrEmpty(dto != null ? dto.getScope() : null));
        out.println("clientAppId=" + valueOrEmpty(dto != null ? dto.getClientAppId() : null));
        out.println("accountId=" + valueOrEmpty(dto != null ? dto.getAccountId() : null));
        out.println("skillId=" + valueOrEmpty(dto != null ? dto.getSkillId() : null));
        out.println("status=" + valueOrEmpty(dto != null ? dto.getStatus() : null));
        if (dto != null && dto.getMaterializeResult() != null) {
            out.println("materializeStatus=" + valueOrEmpty(dto.getMaterializeResult().getStatus()));
            out.println("workerStatusCode=" + valueOrEmpty(dto.getMaterializeResult().getWorkerStatusCode()));
        }
    }

    private String normalizeSkillBundleScope(String scope) {
        String value = scope == null ? "" : scope.trim().replace('-', '_').toUpperCase();
        if ("CLIENT_APP_PUBLIC".equals(value) || "PUBLIC".equals(value)) {
            return "CLIENT_APP_PUBLIC";
        }
        if ("ACCOUNT_PRIVATE".equals(value) || "ACCOUNT".equals(value) || "PRIVATE".equals(value)) {
            return "ACCOUNT_PRIVATE";
        }
        throw new UpstreamCliException("invalid scope: " + scope);
    }

    private void printMessages(List<SessionMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            out.println("messages=0");
            return;
        }
        for (SessionMessage message : messages) {
            out.println("message id=" + valueOrEmpty(message.getMessageId())
                    + " role=" + valueOrEmpty(message.getRole())
                    + " type=" + valueOrEmpty(message.getType())
                    + " content=" + redact(truncate(message.getContent(), 500)));
        }
    }

    private String redact(String text) {
        return SecretMasker.redactKnownSecrets(valueOrEmpty(text), config.sensitiveValues());
    }

    private String requiredOption(CliArguments args, String option, String description) {
        String value = args.option(option);
        if (!hasText(value)) {
            throw new UpstreamCliException(description + " is required (--" + option + ")");
        }
        return value;
    }

    private String requiredOptionOrConfig(CliArguments args, String option, String key, String description) {
        String value = args.option(option);
        if (hasText(value)) {
            return value;
        }
        return config.required(key, description);
    }

    private Integer parseInteger(String value) {
        if (!hasText(value)) {
            return null;
        }
        return parseInteger(value, 0);
    }

    private int parseInteger(String value, int defaultValue) {
        if (!hasText(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new UpstreamCliException("Expected integer but got: " + value);
        }
    }

    private static boolean isSensitiveKey(String key) {
        return key.endsWith("_SECRET") || key.endsWith("_TOKEN")
                || key.endsWith("_API_KEY") || key.endsWith("_KEY");
    }

    private static String valueOrEmpty(Object value) {
        return value == null ? "(empty)" : String.valueOf(value);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
