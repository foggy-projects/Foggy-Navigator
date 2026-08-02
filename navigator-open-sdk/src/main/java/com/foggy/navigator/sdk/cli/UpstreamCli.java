package com.foggy.navigator.sdk.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.foggy.navigator.sdk.api.AgentApi;
import com.foggy.navigator.sdk.api.BusinessAgentApi;
import com.foggy.navigator.sdk.api.DirectoryApi;
import com.foggy.navigator.sdk.api.ManagementAuthApi;
import com.foggy.navigator.sdk.api.WorkerApi;
import com.foggy.navigator.sdk.exception.NavigatorApiException;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.AgentTask;
import com.foggy.navigator.sdk.model.AgentReadiness;
import com.foggy.navigator.sdk.model.AgentReadinessCheck;
import com.foggy.navigator.sdk.model.Directory;
import com.foggy.navigator.sdk.model.PhysicalWorkerDiagnostic;
import com.foggy.navigator.sdk.model.SessionListPage;
import com.foggy.navigator.sdk.model.SessionMessage;
import com.foggy.navigator.sdk.model.SessionMessagesPage;
import com.foggy.navigator.sdk.model.SessionSummary;
import com.foggy.navigator.sdk.model.SkillArtifactFile;
import com.foggy.navigator.sdk.model.SkillArtifactSlice;
import com.foggy.navigator.sdk.model.SkillArtifactTree;
import com.foggy.navigator.sdk.model.TaskDiagnostics;
import com.foggy.navigator.sdk.model.TaskEvidence;
import com.foggy.navigator.sdk.model.TaskMessagesPage;
import com.foggy.navigator.sdk.model.Worker;
import com.foggy.navigator.sdk.model.businessagent.AccountContextFileDTO;
import com.foggy.navigator.sdk.model.businessagent.AccountContextFileTreeDTO;
import com.foggy.navigator.sdk.model.businessagent.AccountContextFileWriteForm;
import com.foggy.navigator.sdk.model.businessagent.AgentModelBindingDTO;
import com.foggy.navigator.sdk.model.businessagent.AgentWorkerBindingDTO;
import com.foggy.navigator.sdk.model.businessagent.AgentWorkspaceBindingDTO;
import com.foggy.navigator.sdk.model.businessagent.ApproveUpstreamBootstrapRequestForm;
import com.foggy.navigator.sdk.model.businessagent.BindAgentModelForm;
import com.foggy.navigator.sdk.model.businessagent.BindAgentWorkerForm;
import com.foggy.navigator.sdk.model.businessagent.BindAgentWorkspaceForm;
import com.foggy.navigator.sdk.model.businessagent.BusinessAgentBundleDTO;
import com.foggy.navigator.sdk.model.businessagent.BusinessFunctionSummaryDTO;
import com.foggy.navigator.sdk.model.businessagent.ClaimUpstreamAdminCredentialForm;
import com.foggy.navigator.sdk.model.businessagent.ClearSkillBundleForm;
import com.foggy.navigator.sdk.model.businessagent.ClientAppDTO;
import com.foggy.navigator.sdk.model.businessagent.ClientAppFunctionGrantDTO;
import com.foggy.navigator.sdk.model.businessagent.ClientAppModelConfigForm;
import com.foggy.navigator.sdk.model.businessagent.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.sdk.model.businessagent.ClientAppRuntimeAccessTokenDTO;
import com.foggy.navigator.sdk.model.businessagent.ClientAppUpstreamRouteDTO;
import com.foggy.navigator.sdk.model.businessagent.ClientAppUpstreamUserGrantDTO;
import com.foggy.navigator.sdk.model.businessagent.CreateUpstreamBootstrapRequestForm;
import com.foggy.navigator.sdk.model.businessagent.DenyUpstreamBootstrapRequestForm;
import com.foggy.navigator.sdk.model.businessagent.EnsureUpstreamClientAppForm;
import com.foggy.navigator.sdk.model.businessagent.EnsureUpstreamTenantClientAppForm;
import com.foggy.navigator.sdk.model.businessagent.GrantBusinessFunctionForm;
import com.foggy.navigator.sdk.model.businessagent.GrantModelConfigForm;
import com.foggy.navigator.sdk.model.businessagent.GrantUpstreamUserForm;
import com.foggy.navigator.sdk.model.businessagent.UpstreamAdminClientAppScopeDTO;
import com.foggy.navigator.sdk.model.businessagent.ImportBusinessFunctionManifestForm;
import com.foggy.navigator.sdk.model.businessagent.IssueControlCredentialForm;
import com.foggy.navigator.sdk.model.businessagent.IssueRuntimeCredentialForm;
import com.foggy.navigator.sdk.model.businessagent.IssuedCredentialDTO;
import com.foggy.navigator.sdk.model.businessagent.LlmModelConfigDTO;
import com.foggy.navigator.sdk.model.businessagent.RotateModelConfigKeyForm;
import com.foggy.navigator.sdk.model.businessagent.RotateUpstreamAdminCredentialForm;
import com.foggy.navigator.sdk.model.businessagent.RuntimeRequestAuditDTO;
import com.foggy.navigator.sdk.model.businessagent.RuntimeRequestAuditPageDTO;
import com.foggy.navigator.sdk.model.businessagent.RuntimeRequestAuditStageDTO;
import com.foggy.navigator.sdk.model.businessagent.RuntimeBindingAuditDTO;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskAuditDTO;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskAuditStageDTO;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskCompletionReadinessDTO;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskReconcileForm;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskReconciliationDTO;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskTerminalCleanupRepairDTO;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskTerminalCleanupRepairForm;
import com.foggy.navigator.sdk.model.businessagent.SkillClearResultDTO;
import com.foggy.navigator.sdk.model.businessagent.SkillBundleDTO;
import com.foggy.navigator.sdk.model.businessagent.SyncAccountSkillBundleForm;
import com.foggy.navigator.sdk.model.businessagent.SyncBusinessAgentBundleForm;
import com.foggy.navigator.sdk.model.businessagent.SyncSkillBundleForm;
import com.foggy.navigator.sdk.model.businessagent.UpstreamAdminCredentialClaimDTO;
import com.foggy.navigator.sdk.model.businessagent.UpstreamAdminCredentialDTO;
import com.foggy.navigator.sdk.model.businessagent.UpstreamAgentForm;
import com.foggy.navigator.sdk.model.businessagent.UpstreamTenantClientAppProvisioningDTO;
import com.foggy.navigator.sdk.model.businessagent.UpstreamBootstrapRequestCreatedDTO;
import com.foggy.navigator.sdk.model.businessagent.UpstreamBootstrapRequestDTO;
import com.foggy.navigator.sdk.model.businessagent.UpsertClientAppUpstreamRouteForm;
import com.foggy.navigator.sdk.model.businessagent.WorkerHostManifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpstreamCli {
    private static final String CREDENTIALS_NOT_REPLAYABLE = "CREDENTIALS_NOT_REPLAYABLE";
    private static final String CLAUDE_WORKER_INSTALL_BASE_URL =
            "https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker";
    private static final String CODEX_WORKER_INSTALL_BASE_URL =
            "https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker";
    private static final String BIZ_WORKER_INSTALL_BASE_URL =
            "https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/langgraph-biz-worker";
    private static final String LANGGRAPH_BIZ_BACKEND = "LANGGRAPH_BIZ";
    private static final String OPENAI_CODEX_BACKEND = "OPENAI_CODEX";
    private static final Pattern BIZ_CONTEXT_ID_PATTERN =
            Pattern.compile("^bctx_(\\d{8})_([0-9a-fA-F]{2})_[A-Za-z0-9._-]+$");
    private static final DateTimeFormatter BIZ_CONTEXT_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String NOT_SUPPLIED_BY_SERVER = "NOT_SUPPLIED_BY_SERVER";
    private static final Pattern MANAGEMENT_REFERENCE_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@#{}-]*");
    private static final Set<String> PLATFORM_CONTROL_PROFILE_FORBIDDEN = Set.of(
            "NAVI_ADMIN_API_KEY", "NAVIGATOR_ADMIN_API_KEY", "NAVI_ADMIN_TOKEN", "NAVIGATOR_ADMIN_TOKEN",
            "NAVI_OPERATOR_API_KEY", "NAVIGATOR_OPERATOR_API_KEY", "NAVI_PRINCIPAL_CREDENTIAL",
            "NAVI_CLIENT_APP_KEY", "CLIENT_APP_KEY", "NAVI_CLIENT_APP_SECRET", "CLIENT_APP_SECRET",
            "NAVI_CLIENT_APP_ACCESS_TOKEN", "NAVI_CLIENT_APP_RUNTIME_TOKEN", "CLIENT_APP_RUNTIME_TOKEN",
            "NAVI_RUNTIME_CREDENTIAL", "NAVI_USER_API_KEY", "NAVI_ADMIN_KEY_CLAIM_TOKEN",
            "NAVI_WORKER_CREDENTIAL", "NAVI_TASK_SCOPED_TOKEN");
    private static final Set<String> TENANT_RUNTIME_PROFILE_FORBIDDEN = Set.of(
            "NAVI_ADMIN_API_KEY", "NAVIGATOR_ADMIN_API_KEY", "NAVI_ADMIN_TOKEN", "NAVIGATOR_ADMIN_TOKEN",
            "NAVI_OPERATOR_API_KEY", "NAVIGATOR_OPERATOR_API_KEY", "NAVI_CONTROL_API_KEY",
            "NAVIGATOR_CONTROL_API_KEY", "NAVI_PRINCIPAL_CREDENTIAL", "NAVI_USER_API_KEY",
            "NAVI_ADMIN_KEY_CLAIM_TOKEN", "NAVI_WORKER_CREDENTIAL", "NAVI_TASK_SCOPED_TOKEN");
    private static final Set<String> SANITIZED_RUNTIME_ERROR_CODES = Set.of(
            "AUDIT_QUERY_MODE_CONFLICT",
            "AUDIT_RECORD_EXPIRED_OR_NOT_FOUND",
            "CLIENT_REQUEST_ID_ALREADY_USED",
            "CLIENT_REQUEST_ID_INVALID",
            "CLIENT_REQUEST_ID_OPERATION_MISMATCH",
            "CLIENT_REQUEST_ID_REQUIRED",
            "FUNCTION_SCOPE_EXPLICIT_NULL",
            "RUNTIME_AUDIT_BOUNDED_WINDOW_REQUIRED",
            "RUNTIME_AUDIT_CREDENTIAL_INVALID",
            "RUNTIME_AUDIT_CREDENTIAL_LANE_REJECTED",
            "RUNTIME_AUDIT_CREDENTIAL_REQUIRED",
            "RUNTIME_AUDIT_HANDLE_REQUIRED",
            "RUNTIME_AUDIT_LIMIT_INVALID",
            "RUNTIME_AUDIT_OPERATION_INVALID",
            "RUNTIME_AUDIT_QUERY_FAILED",
            "RUNTIME_AUDIT_RECORDING_FAILED",
            "RUNTIME_AUDIT_RECORD_NOT_FOUND",
            "RUNTIME_AUDIT_SCOPE_NOT_FOUND",
            "RUNTIME_AUDIT_SERVICE_UNAVAILABLE",
            "RUNTIME_AUDIT_SINCE_INVALID",
            "RUNTIME_AUDIT_UNTIL_INVALID",
            "RUNTIME_AUDIT_WINDOW_INVALID",
            "RUNTIME_AUDIT_WINDOW_TOO_LARGE",
            "RUNTIME_BINDING_AUDIT_AGENT_MISMATCH",
            "RUNTIME_BINDING_AUDIT_AGENT_REQUIRED",
            "RUNTIME_BINDING_AUDIT_DIRECTORY_MISMATCH",
            "RUNTIME_BINDING_AUDIT_DIRECTORY_REQUIRED",
            "RUNTIME_BINDING_AUDIT_MODEL_MISMATCH",
            "RUNTIME_BINDING_AUDIT_MODEL_REQUIRED",
            "RUNTIME_BINDING_AUDIT_NOT_FOUND",
            "RUNTIME_BINDING_AUDIT_QUERY_FAILED",
            "RUNTIME_BINDING_AUDIT_UPSTREAM_USER_REQUIRED",
            "RUNTIME_BINDING_AUDIT_WORKER_MISMATCH",
            "RUNTIME_BINDING_AUDIT_WORKER_NOT_FOUND",
            "RUNTIME_CLIENT_APP_CREDENTIAL_REQUIRED",
            "RUNTIME_CLIENT_APP_KEY_REQUIRED",
            "RUNTIME_CLIENT_APP_KEY_UNKNOWN",
            "RUNTIME_CLIENT_APP_SCOPE_UNKNOWN",
            "RUNTIME_CREDENTIAL_EXPIRED",
            "RUNTIME_CREDENTIAL_INACTIVE",
            "RUNTIME_CREDENTIAL_INVALID",
            "RUNTIME_CREDENTIAL_REQUIRED",
            "RUNTIME_STATE_AUDIT_CREDENTIAL_LANE_REJECTED",
            "RUNTIME_STATE_AUDIT_SERVICE_UNAVAILABLE",
            "RUNTIME_TASK_AUDIT_FORBIDDEN",
            "RUNTIME_TASK_AUDIT_NOT_FOUND",
            "RUNTIME_TASK_AUDIT_QUERY_FAILED",
            "RUNTIME_TASK_AUDIT_TASK_REQUIRED",
            "RUNTIME_TASK_AUDIT_UPSTREAM_USER_REQUIRED",
            "RUNTIME_TASK_FORBIDDEN",
            "RUNTIME_TASK_NOT_FOUND",
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_ALREADY_COMPLETE",
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_BODY_REQUIRED",
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_DRY_RUN_REQUIRED",
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_NOT_READY",
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_REPLAY_PROHIBITED",
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_SERVICE_UNAVAILABLE",
            "SAFE_SMOKE_BODY_REQUIRED",
            "SAFE_SMOKE_FUNCTION_SCOPE_REQUIRED",
            "SAFE_SMOKE_MAX_TURNS_MUST_BE_ONE",
            "SAFE_SMOKE_MESSAGE_REQUIRED",
            "SAFE_SMOKE_REJECTED",
            "SAFE_SMOKE_REQUIRES_EMPTY_FUNCTION_SCOPE",
            "SAFE_SMOKE_REQUIRES_EMPTY_TOOL_SCOPE",
            "SAFE_SMOKE_RUNTIME_INPUT_NOT_ALLOWED",
            "SAFE_SMOKE_TOKEN_SERVICE_UNAVAILABLE",
            "SAFE_SMOKE_TOOL_SCOPE_REQUIRED",
            "SAFE_SMOKE_UPSTREAM_USER_REQUIRED",
            "TOOL_SCOPE_EXPLICIT_NULL");
    private final PrintStream out;
    private final PrintStream err;
    private final Path cwd;
    private final ObjectMapper objectMapper;
    private final CommandRunner commandRunner;
    private UpstreamCliConfig config;
    private String resolvedClientAppAccessToken;
    private String activeClientRequestId;
    private String activeRuntimeOperation;
    private String activeRuntimeAgentCode;
    private String activeRuntimeUpstreamUserId;
    private Map<String, String> env = Map.of();

    public UpstreamCli(PrintStream out, PrintStream err, Path cwd) {
        this(out, err, cwd, new ProcessCommandRunner());
    }

    UpstreamCli(PrintStream out, PrintStream err, Path cwd, CommandRunner commandRunner) {
        this.out = out;
        this.err = err;
        this.cwd = cwd;
        this.commandRunner = commandRunner;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
            config = null;
            activeClientRequestId = null;
            activeRuntimeOperation = null;
            activeRuntimeAgentCode = null;
            activeRuntimeUpstreamUserId = null;
            parsed.rejectUnknownOptions();
            if (parsed.flag("help")) {
                return help(parsed);
            }
            this.env = env != null ? env : Map.of();
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

    private int help(CliArguments args) {
        String command = args.command();
        printLegacyMigrationNotice(command);
        if (command.startsWith("runtime ") || "runtime".equals(command)) {
            return switch (command) {
                case "runtime token" -> runtimeTokenUsage();
                case "runtime readiness", "runtime verify-agent-readiness" -> runtimeReadinessUsage();
                case "runtime owner-smoke" -> runtimeOwnerSmokeUsage();
                default -> runtimeUsage();
            };
        }
        if (command.startsWith("platform ") || "platform".equals(command)) {
            if (command.startsWith("platform app-scope")) {
                return platformAppScopeUsage();
            }
            if (command.startsWith("platform app ")) {
                return platformAppUsage();
            }
            if (command.startsWith("platform agent")) {
                return platformAgentUsage();
            }
            if (command.startsWith("platform model")) {
                return platformModelUsage();
            }
            if (command.startsWith("platform worker-host")) {
                return workerHostUsage();
            }
            if (command.startsWith("platform worker-pool")) {
                return workerPoolUsage();
            }
            if (command.startsWith("platform worker")) {
                return workerUsage();
            }
            if (command.startsWith("platform directory")) {
                return directoryUsage();
            }
            return platformUsage();
        }
        if (command.startsWith("app ") || "app".equals(command)) {
            return appUsage();
        }
        return switch (command) {
            case "config check" -> configCheckUsage();
            case "runtime-token" -> runtimeTokenUsage();
            case "owner-smoke" -> runtimeOwnerSmokeUsage();
            case "verify-agent-readiness", "verify-agent-grant" -> runtimeReadinessUsage();
            case "auth", "auth login" -> authUsage();
            case "auth whoami" -> authWhoamiUsage();
            case "inspect permissions" -> inspectPermissionsUsage();
            case "client-app" -> clientAppUsage();
            case "agent" -> agentUsage();
            case "model" -> modelUsage();
            case "worker" -> workerUsage();
            case "worker-host" -> workerHostUsage();
            case "worker-pool" -> workerPoolUsage();
            case "directory" -> directoryUsage();
            case "function" -> functionUsage();
            case "route" -> routeUsage();
            case "diagnostics", "diagnostics session-dir" -> diagnosticsUsage();
            case "admin-key" -> adminKeyUsage();
            case "", "help" -> usage();
            default -> usage();
        };
    }

    private int dispatch(CliArguments args) throws Exception {
        printLegacyMigrationNotice(args.command());
        if (isRuntimeOperation(args.command())) {
            requireRuntimeLane();
        }
        return switch (args.command()) {
            case "config check" -> configCheck();
            case "platform", "platform help", "platform tenant", "platform app" -> platformUsage();
            case "platform tenant list" -> upstreamClientAppList(args);
            case "platform tenant ensure" -> upstreamTenantClientAppEnsure(args, false);
            case "platform app list" -> args.flag("help") ? platformAppUsage() : upstreamClientAppList(args);
            case "platform app ensure" -> upstreamClientAppEnsure(args);
            case "platform app issue-control-key" -> upstreamClientAppIssueControlKey(args, false);
            case "platform app issue-runtime-key", "platform app issue-runtime-credential" ->
                    upstreamClientAppIssueRuntimeKey(args, false);
            case "platform app-scope" -> platformAppScopeUsage();
            case "platform app-scope inspect" -> platformAppScopeInspect(args);
            case "platform app-scope agent-list" -> platformAppScopeAgentList(args);
            case "platform app-scope agent-get" -> platformAppScopeAgentGet(args);
            case "platform app-scope agent-sync" -> platformAppScopeAgentSync(args);
            case "platform app-scope model-grants" -> platformAppScopeModelGrants(args);
            case "platform app-scope model-grant" -> platformAppScopeModelGrant(args);
            case "platform app-scope model-set-default" -> platformAppScopeModelSetDefault(args);
            case "platform app-scope model-get" -> platformAppScopeModelGet(args);
            case "platform app-scope model-create" -> platformAppScopeModelCreate(args);
            case "platform app-scope model-update" -> platformAppScopeModelUpdate(args);
            case "platform app-scope model-rotate-key" -> platformAppScopeModelRotateKey(args, false);
            case "platform app-scope model-clear-key" -> platformAppScopeModelRotateKey(args, true);
            case "platform app-scope user-grants" -> platformAppScopeUserGrants(args);
            case "platform app-scope user-grant" -> platformAppScopeUserGrant(args);
            case "platform app-scope user-status" -> platformAppScopeUserStatus(args);
            case "platform app-scope model-bindings" -> platformAppScopeModelBindings(args);
            case "platform app-scope bind-model" -> platformAppScopeBindModel(args);
            case "platform app-scope unbind-model" -> platformAppScopeUnbindModel(args);
            case "platform app-scope set-default-model" -> platformAppScopeSetDefaultModel(args);
            case "platform app-scope workspace-bindings" -> platformAppScopeWorkspaceBindings(args);
            case "platform app-scope bind-workspace" -> platformAppScopeBindWorkspace(args);
            case "platform app-scope unbind-workspace" -> platformAppScopeUnbindWorkspace(args);
            case "platform app-scope set-default-workspace" -> platformAppScopeSetDefaultWorkspace(args);
            case "platform app-scope worker-bindings" -> platformAppScopeWorkerBindings(args);
            case "platform app-scope bind-worker" -> platformAppScopeBindWorker(args);
            case "platform app-scope unbind-worker" -> platformAppScopeUnbindWorker(args);
            case "platform app-scope set-default-worker" -> platformAppScopeSetDefaultWorker(args);
            case "platform app-scope directory-list" -> platformAppScopeDirectoryList(args);
            case "platform app-scope directory-init" -> platformAppScopeDirectoryInit(args);
            case "platform app-scope directory-get" -> platformAppScopeDirectoryGet(args);
            case "platform app-scope directory-delete" -> platformAppScopeDirectoryDelete(args);
            case "platform app-scope directory-env" -> platformAppScopeDirectoryEnv(args);
            case "platform app-scope directory-files" -> platformAppScopeDirectoryFiles(args);
            case "platform agent" -> platformAgentUsage();
            case "platform agent list" -> agentSystemList(args);
            case "platform agent create" -> agentSystemCreate(args);
            case "platform agent get" -> agentSystemGet(args);
            case "platform agent update" -> agentSystemUpdate(args);
            case "platform agent model-bindings" -> agentSystemModelBindings(args);
            case "platform agent bind-model" -> agentSystemBindModel(args);
            case "platform agent unbind-model" -> agentSystemUnbindModel(args);
            case "platform agent set-default-model" -> agentSystemSetDefaultModel(args);
            case "platform agent workspace-bindings" -> agentSystemWorkspaceBindings(args);
            case "platform agent bind-workspace" -> agentSystemBindWorkspace(args);
            case "platform agent unbind-workspace" -> agentSystemUnbindWorkspace(args);
            case "platform agent set-default-workspace" -> agentSystemSetDefaultWorkspace(args);
            case "platform agent worker-bindings" -> agentSystemWorkerBindings(args);
            case "platform agent bind-worker" -> agentSystemBindWorker(args);
            case "platform agent unbind-worker" -> agentSystemUnbindWorker(args);
            case "platform agent set-default-worker" -> agentSystemSetDefaultWorker(args);
            case "platform model" -> platformModelUsage();
            case "platform model list" -> modelSystemList(args);
            case "platform model get" -> modelSystemGet(args);
            case "platform model create" -> modelSystemCreate(args);
            case "platform model update" -> modelSystemUpdate(args);
            case "platform model test", "platform model test-connection" -> modelSystemTest(args);
            case "platform model test-saved" -> modelSystemTestSaved(args);
            case "platform model rotate-key" -> modelSystemRotateKey(args);
            case "platform model clear-key" -> modelSystemClearKey(args);
            case "platform worker" -> workerUsage();
            case "platform worker list" -> workerList(args);
            case "platform worker create" -> workerCreate(args);
            case "platform worker get" -> workerGet(args);
            case "platform worker update" -> workerUpdate(args);
            case "platform worker delete" -> workerDelete(args);
            case "platform worker health" -> workerHealth(args);
            case "platform worker processes" -> workerProcesses(args);
            case "platform worker kill" -> workerKill(args);
            case "platform worker-host" -> workerHostUsage();
            case "platform worker-host apply" -> workerHostApply(args);
            case "platform worker-host update" -> workerHostUpdate(args);
            case "platform worker-host verify" -> workerHostVerify(args);
            case "platform worker-host install" -> workerHostInstall(args);
            case "platform directory" -> directoryUsage();
            case "platform directory list" -> directoryList(args);
            case "platform directory init" -> directoryInit(args);
            case "platform directory get" -> directoryGet(args);
            case "platform directory delete" -> directoryDelete(args);
            case "platform directory env" -> directoryEnv(args);
            case "platform directory files" -> directoryFiles(args);
            case "platform worker-pool" -> workerPoolUsage();
            case "platform worker-pool list" -> workerPoolList(args);
            case "platform worker-pool create" -> workerPoolCreate(args);
            case "platform worker-pool register-worker" -> workerPoolRegisterWorker(args);
            case "platform worker-pool add-member" -> workerPoolAddMember(args);
            case "platform worker-pool status" -> workerPoolStatus(args);
            case "app", "app help", "app agent", "app model", "app directory", "app function", "app route" -> appUsage();
            case "app ensure-grant" -> ensureGrant(args);
            case "app agent sync" -> agentSync(args);
            case "app agent model-bindings" -> agentModelBindings(args);
            case "app agent bind-model" -> agentBindModel(args);
            case "app agent unbind-model" -> agentUnbindModel(args);
            case "app agent set-default-model" -> agentSetDefaultModel(args);
            case "app agent workspace-bindings" -> agentWorkspaceBindings(args);
            case "app agent bind-workspace" -> agentBindWorkspace(args);
            case "app agent unbind-workspace" -> agentUnbindWorkspace(args);
            case "app agent set-default-workspace" -> agentSetDefaultWorkspace(args);
            case "app agent worker-bindings" -> agentWorkerBindings(args);
            case "app agent bind-worker" -> agentBindWorker(args);
            case "app agent unbind-worker" -> agentUnbindWorker(args);
            case "app agent set-default-worker" -> agentSetDefaultWorker(args);
            case "app model grants" -> modelGrants(args);
            case "app model grant" -> modelGrant(args);
            case "app model set-default" -> modelSetDefault(args);
            case "app model create" -> modelCreate(args);
            case "app model update" -> modelUpdate(args);
            case "app model test", "app model test-connection" -> modelTest(args);
            case "app model test-saved" -> modelTestSaved(args);
            case "app model rotate-key" -> modelRotateKey(args);
            case "app model clear-key" -> modelClearKey(args);
            case "app function import" -> functionImport(args);
            case "app function grant" -> functionGrant(args);
            case "app function grant-status" -> functionGrantStatus(args);
            case "app function visible" -> functionVisible(args);
            case "app route list" -> routeList(args);
            case "app route set" -> routeSet(args);
            case "app route status" -> routeStatus(args);
            case "app directory list" -> directoryClientList(args);
            case "app directory init" -> directoryClientInit(args);
            case "app directory get" -> directoryClientGet(args);
            case "app directory delete" -> directoryClientDelete(args);
            case "app directory env" -> directoryClientEnv(args);
            case "app directory files" -> directoryClientFiles(args);
            case "runtime", "runtime help" -> runtimeUsage();
            case "runtime token" -> runtimeToken(args);
            case "runtime audit" -> runtimeAudit(args);
            case "runtime binding-audit" -> runtimeBindingAudit(args);
            case "runtime task-audit" -> runtimeTaskAudit(args);
            case "runtime task-completion-readiness" -> runtimeTaskCompletionReadiness(args);
            case "runtime termination-readiness" -> runtimeTerminationReadiness(args);
            case "runtime task-terminate" -> runtimeTaskTerminate(args);
            case "runtime task-reconcile" -> runtimeTaskReconcile(args);
            case "runtime task-terminal-cleanup-repair" -> runtimeTaskTerminalCleanupRepair(args);
            case "runtime owner-smoke" -> ownerSmoke(args);
            case "runtime readiness", "runtime verify-agent-readiness" -> verifyAgentReadiness(args);
            case "runtime inspect" -> inspectRuntime(args);
            case "runtime ask" -> ask(args);
            case "runtime safe-ask" -> safeAsk(args);
            case "runtime messages" -> messages(args);
            case "runtime diagnostics" -> diagnostics(args);
            case "runtime evidence" -> evidence(args);
            case "runtime sessions" -> sessions(args);
            case "runtime session-messages" -> sessionMessages(args);
            case "runtime skill tree" -> skillTree(args);
            case "runtime skill read" -> skillRead(args);
            case "runtime account-context list" -> accountContextList(args);
            case "runtime account-context read" -> accountContextRead(args);
            case "runtime account-context write-policy" -> accountContextWritePolicy(args);
            case "auth", "auth help" -> authUsage();
            case "auth login" -> authLogin(args);
            case "auth whoami" -> authWhoami(args);
            case "runtime-token" -> runtimeToken(args);
            case "owner-smoke" -> ownerSmoke(args);
            case "verify-agent-readiness", "verify-agent-grant" -> verifyAgentReadiness(args);
            case "inspect", "inspect runtime" -> inspectRuntime(args);
            case "inspect permissions" -> inspectPermissions(args);
            case "ensure-grant" -> ensureGrant(args);
            case "ask" -> ask(args);
            case "safe-ask" -> safeAsk(args);
            case "messages" -> messages(args);
            case "diagnostics session-dir" -> diagnosticsSessionDir(args);
            case "diagnostics help" -> diagnosticsUsage();
            case "diagnostics" -> args.flag("help") ? diagnosticsUsage() : diagnostics(args);
            case "evidence" -> evidence(args);
            case "sessions" -> sessions(args);
            case "session-messages" -> sessionMessages(args);
            case "skill tree" -> skillTree(args);
            case "skill read" -> skillRead(args);
            case "skill sync" -> skillSync(args);
            case "skill clear-public" -> skillClearPublic(args);
            case "skill clear-account" -> skillClearAccount(args);
            case "agent", "agent help" -> agentUsage();
            case "agent sync" -> agentSync(args);
            case "agent model-bindings" -> agentModelBindings(args);
            case "agent bind-model" -> agentBindModel(args);
            case "agent unbind-model" -> agentUnbindModel(args);
            case "agent set-default-model" -> agentSetDefaultModel(args);
            case "agent workspace-bindings" -> agentWorkspaceBindings(args);
            case "agent bind-workspace" -> agentBindWorkspace(args);
            case "agent unbind-workspace" -> agentUnbindWorkspace(args);
            case "agent set-default-workspace" -> agentSetDefaultWorkspace(args);
            case "agent worker-bindings" -> agentWorkerBindings(args);
            case "agent bind-worker" -> agentBindWorker(args);
            case "agent unbind-worker" -> agentUnbindWorker(args);
            case "agent set-default-worker" -> agentSetDefaultWorker(args);
            case "agent system-model-bindings" -> agentSystemModelBindings(args);
            case "agent system-bind-model" -> agentSystemBindModel(args);
            case "agent system-unbind-model" -> agentSystemUnbindModel(args);
            case "agent system-set-default-model" -> agentSystemSetDefaultModel(args);
            case "agent system-workspace-bindings" -> agentSystemWorkspaceBindings(args);
            case "agent system-bind-workspace" -> agentSystemBindWorkspace(args);
            case "agent system-unbind-workspace" -> agentSystemUnbindWorkspace(args);
            case "agent system-set-default-workspace" -> agentSystemSetDefaultWorkspace(args);
            case "agent system-worker-bindings" -> agentSystemWorkerBindings(args);
            case "agent system-bind-worker" -> agentSystemBindWorker(args);
            case "agent system-unbind-worker" -> agentSystemUnbindWorker(args);
            case "agent system-set-default-worker" -> agentSystemSetDefaultWorker(args);
            case "agent system-list" -> agentSystemList(args);
            case "agent system-create" -> agentSystemCreate(args);
            case "agent system-get" -> agentSystemGet(args);
            case "agent system-update" -> agentSystemUpdate(args);
            case "function", "function help" -> functionUsage();
            case "function import" -> functionImport(args);
            case "function grant" -> functionGrant(args);
            case "function grant-status" -> functionGrantStatus(args);
            case "function visible" -> functionVisible(args);
            case "route", "route help" -> routeUsage();
            case "route list" -> routeList(args);
            case "route set" -> routeSet(args);
            case "route status" -> routeStatus(args);
            case "model", "model help" -> modelUsage();
            case "model grants" -> modelGrants(args);
            case "model grant" -> modelGrant(args);
            case "model set-default" -> modelSetDefault(args);
            case "model create" -> modelCreate(args);
            case "model update" -> modelUpdate(args);
            case "model test", "model test-connection" -> modelTest(args);
            case "model test-saved" -> modelTestSaved(args);
            case "model rotate-key" -> modelRotateKey(args);
            case "model clear-key" -> modelClearKey(args);
            case "model system-list" -> modelSystemList(args);
            case "model system-get" -> modelSystemGet(args);
            case "model system-create" -> modelSystemCreate(args);
            case "model system-update" -> modelSystemUpdate(args);
            case "model system-test", "model system-test-connection" -> modelSystemTest(args);
            case "model system-test-saved" -> modelSystemTestSaved(args);
            case "model system-rotate-key" -> modelSystemRotateKey(args);
            case "model system-clear-key" -> modelSystemClearKey(args);
            case "admin-key", "admin-key help" -> adminKeyUsage();
            case "admin-key inspect" -> adminKeyInspect(args);
            case "admin-key request" -> adminKeyRequest(args);
            case "admin-key status" -> adminKeyStatus(args);
            case "admin-key claim" -> adminKeyClaim(args);
            case "admin-key list" -> adminKeyList(args);
            case "admin-key approve" -> adminKeyApprove(args);
            case "admin-key deny" -> adminKeyDeny(args);
            case "admin-key revoke" -> adminKeyRevoke(args);
            case "admin-key rotate" -> adminKeyRotate(args);
            case "client-app", "client-app help" -> clientAppUsage();
            case "client-app list" -> upstreamClientAppList(args);
            case "client-app ensure" -> upstreamClientAppEnsure(args);
            case "client-app ensure-tenant" -> upstreamTenantClientAppEnsure(args, true);
            case "client-app issue-control-key" -> upstreamClientAppIssueControlKey(args, true);
            case "client-app issue-runtime-key", "client-app issue-runtime-credential" ->
                    upstreamClientAppIssueRuntimeKey(args, true);
            case "worker", "worker help" -> workerUsage();
            case "worker list" -> workerList(args);
            case "worker create" -> workerCreate(args);
            case "worker get" -> workerGet(args);
            case "worker update" -> workerUpdate(args);
            case "worker delete" -> workerDelete(args);
            case "worker health" -> workerHealth(args);
            case "worker processes" -> workerProcesses(args);
            case "worker kill" -> workerKill(args);
            case "worker-host", "worker-host help" -> workerHostUsage();
            case "worker-host apply" -> workerHostApply(args);
            case "worker-host update" -> workerHostUpdate(args);
            case "worker-host verify" -> workerHostVerify(args);
            case "worker-host install" -> workerHostInstall(args);
            case "directory", "directory help" -> directoryUsage();
            case "directory list" -> directoryList(args);
            case "directory init" -> directoryInit(args);
            case "directory get" -> directoryGet(args);
            case "directory delete" -> directoryDelete(args);
            case "directory env" -> directoryEnv(args);
            case "directory files" -> directoryFiles(args);
            case "directory client-list" -> directoryClientList(args);
            case "directory client-init" -> directoryClientInit(args);
            case "directory client-get" -> directoryClientGet(args);
            case "directory client-delete" -> directoryClientDelete(args);
            case "directory client-env" -> directoryClientEnv(args);
            case "directory client-files" -> directoryClientFiles(args);
            case "worker-pool", "worker-pool help" -> workerPoolUsage();
            case "worker-pool list" -> workerPoolList(args);
            case "worker-pool create" -> workerPoolCreate(args);
            case "worker-pool register-worker" -> workerPoolRegisterWorker(args);
            case "worker-pool add-member" -> workerPoolAddMember(args);
            case "worker-pool status" -> workerPoolStatus(args);
            case "account-context list" -> accountContextList(args);
            case "account-context read" -> accountContextRead(args);
            case "account-context write-policy" -> accountContextWritePolicy(args);
            case "tms token issue-staff", "tms order create-self-pickup-sign-ready",
                    "tms order readiness" -> unsupportedTmsHelper();
            case "", "help" -> usage();
            default -> throw new UpstreamCliException("Unknown command: " + args.command());
        };
    }

    private boolean isRuntimeOperation(String command) {
        return command.startsWith("runtime ")
                || Set.of("runtime-token", "owner-smoke", "verify-agent-readiness", "verify-agent-grant",
                "inspect runtime", "ask", "safe-ask", "messages", "diagnostics", "evidence", "sessions", "session-messages")
                .contains(command);
    }

    private int usage() {
        out.println("Usage: navi upstream <command> [options]");
        out.println("Canonical lanes: platform, app, runtime. Run `navi upstream <lane> --help` for lane-specific commands.");
        out.println("Commands: config check, auth login/whoami, runtime-token, runtime audit/binding-audit/task-audit, owner-smoke, inspect runtime/permissions, verify-agent-readiness, verify-agent-grant, ensure-grant, ask, safe-ask, messages, diagnostics, diagnostics session-dir, evidence, sessions, session-messages, skill tree, skill read, skill sync, skill clear-public, skill clear-account, agent sync, agent model-bindings/bind-model/unbind-model/set-default-model, agent workspace-bindings/bind-workspace/unbind-workspace/set-default-workspace, agent worker-bindings/bind-worker/unbind-worker/set-default-worker, agent system-list/system-create/system-get/system-update, agent system-model-bindings/system-bind-model/system-unbind-model/system-set-default-model, agent system-workspace-bindings/system-bind-workspace/system-unbind-workspace/system-set-default-workspace, agent system-worker-bindings/system-bind-worker/system-unbind-worker/system-set-default-worker, function import, function grant, function grant-status, function visible, route list, route set, route status, model grants, model grant, model set-default, model create, model update, model test/test-saved, model rotate-key, model clear-key, model system-list/system-get/system-create/system-update/system-test/system-test-saved/system-rotate-key/system-clear-key, admin-key request, admin-key status, admin-key claim, admin-key list, admin-key approve, admin-key deny, admin-key revoke, admin-key rotate, client-app list, client-app ensure, client-app ensure-tenant, client-app issue-runtime-key, client-app issue-control-key, worker-host apply/update/verify/install, worker list/create/get/update/delete/health/processes/kill, directory list/init/get/delete/env/files/client-list/client-init/client-get/client-delete/client-env/client-files, account-context list, account-context read, account-context write-policy");
        out.println("Legacy internal compatibility only: worker-pool list/create/register-worker/add-member/status. Do not use these commands to onboard OPENAI_CODEX or OPENAI_CODEX_APP_SERVER.");
        out.println("For an existing Physical Worker, use worker-host verify then update; use apply only for a new WorkerHost.");
        out.println("Typed-management introspection requires exactly one NAVI_PRINCIPAL_CREDENTIAL (or --principal-credential-env); NAVI_ADMIN_API_KEY is not S1 root or S2 platform/security authority.");
        out.println("NAVIGATOR_EXTERNAL_ENABLED gates only /api/v1/open/**. It is not Provider, Worker Gateway, Worker, or production readiness.");
        out.println("NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED is Worker-principal strictness, not network exposure; it remains unavailable until every caller propagates the complete principal headers.");
        CliProvenance provenance = CliProvenance.load();
        out.println("CLI provenance: source=" + provenance.sourceVersion() + ", published=" + provenance.publishedVersion()
                + ", artifactDrift=" + provenance.artifactDrift() + " (not a release claim).");
        out.println("  owner-smoke --upstream-user-id <id> [--agent-code <id>] [--model-config-id <id>] [--model-variant <name>] [--directory-id <id>] [--no-directory-required]");
        out.println("  ask --upstream-user-id <id> --message <text> [--context-id <returnedContextId>] [--max-turns <n>] [--model-config-id <id>] [--model-variant <name>] [--directory-id <id>] [--provider-type codex-biz-worker] [--private-account-id <id>|--codex-home-key <key>] [--allowed-tools <csv|none>] [--allowed-functions <csv|none>] [--client-context-json <json>|--client-context-file <path>]");
        out.println("  safe-ask --upstream-user-id <id> --message <label> [--model-config-id <id>] [--model-variant <name>]  # forces maxTurns=1 and exact allowedTools=[]/allowedFunctions=[]; no Worker/model dispatch");
        out.println("  runtime audit --request-id <clientRequestId> [--operation runtime-token|safe-ask|ask|task-terminate|task-reconcile|task-terminal-cleanup-repair] [--json]");
        out.println("  runtime audit --since <offset-time> --until <offset-time> [--operation runtime-token|safe-ask|ask|task-terminate|task-reconcile|task-terminal-cleanup-repair] [--agent-code <id>] [--upstream-user-id <id>] [--limit <1..100>] [--json]");
        out.println("  runtime binding-audit --agent-code <id> --upstream-user-id <id> --model-config-id <id> --directory-id <id> [--json]");
        out.println("  runtime task-audit --task-id <existingTaskId> [--upstream-user-id <id>] [--json]");
        out.println("  messages --task-id <taskId> --agent-code <agentId> [--poll] [--interval <seconds>]");
        out.println("  diagnostics --task-id <taskId> --agent-code <agentId> [--upstream-user-id <id>]");
        out.println("  diagnostics session-dir --context-id <contextId> [--task-id <taskId>] [--provider-task-id <providerTaskId>] [--worker-backend LANGGRAPH_BIZ|OPENAI_CODEX] [--data-root <bizWorkerDataRoot>] [--biz-worker-env-file <path>] [--codex-workspace-root <path>]");
        out.println("  evidence --task-id <taskId> --agent-code <agentId> [--upstream-user-id <id>]");
        out.println("    New sessions should omit --context-id; reuse the returned contextId only for continuation. clientContext is metadata, not prompt/model-budget config.");
        out.println("  model create/update uses NAVI_CONTROL_API_KEY and creates ClientApp-owned models.");
        out.println("  model system-create/system-update uses NAVI_ADMIN_API_KEY and creates UpstreamSystem-owned shared models.");
        out.println("  model create/system-create accepts --worker-backend LANGGRAPH_BIZ|OPENAI_CODEX|OPENAI_CODEX_APP_SERVER|CLAUDE_CODE|GEMINI_CLI.");
        return 0;
    }

    private int authUsage() {
        out.println("Usage: navi upstream auth <command> [options]");
        out.println("Commands: login, whoami");
        out.println("  login --base-url <navigatorBaseUrl> --username <username> --password-env <envName> --write-profile");
        out.println("  whoami [--principal-credential-env <envName>]");
        out.println("whoami is typed-management-only. It uses exactly one NAVI_PRINCIPAL_CREDENTIAL and never falls back to NAVI_ADMIN_API_KEY, control, runtime, user, task, or Worker credentials.");
        out.println("Stores NAVI_ADMIN_TOKEN in the gitignored project profile for admin-key approval. The password is read from an environment variable and is never printed.");
        return 0;
    }

    private int authLogin(CliArguments args) throws Exception {
        if (!args.flag("write-profile")) {
            throw new UpstreamCliException("auth login requires --write-profile to store NAVI_ADMIN_TOKEN without printing it");
        }
        config.assertProfileWritable();
        String baseUrl = requiredOptionOrConfig(args, "base-url", "NAVI_BASE_URL", "Navigator base URL");
        String username = requiredOption(args, "username", "Navigator username");
        String passwordEnv = requiredOption(args, "password-env", "Navigator password env");
        String password = env.get(passwordEnv);
        if (!hasText(password)) {
            throw new UpstreamCliException("environment variable " + passwordEnv + " is required");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        Map<String, Object> login = new HttpHelper(baseUrl, null, Duration.ofSeconds(30))
                .postNoAuth("/api/v1/auth/login", body, new TypeReference<>() {});
        String token = stringValue(login.get("token"));
        if (!hasText(token)) {
            throw new UpstreamCliException("auth login response did not include token");
        }
        Map<String, Object> user = objectMap(login.get("user"));
        String tenantId = stringValue(user.get("tenantId"));
        String userId = stringValue(user.get("id"));
        String resolvedUsername = firstNonBlank(stringValue(user.get("username")), username);
        String roles = stringValue(user.get("roles"));

        config.writeProfileValue("NAVI_BASE_URL", baseUrl);
        config.writeProfileValue("NAVI_ADMIN_TOKEN", token);
        if (hasText(tenantId)) {
            config.writeProfileValue("NAVI_TENANT_ID", tenantId);
        }
        if (hasText(userId)) {
            config.writeProfileValue("NAVI_ADMIN_USER_ID", userId);
        }
        if (hasText(resolvedUsername)) {
            config.writeProfileValue("NAVI_ADMIN_USERNAME", resolvedUsername);
        }

        out.println("auth login ok");
        out.println("userId=" + valueOrEmpty(userId));
        out.println("username=" + redact(resolvedUsername));
        out.println("tenantId=" + valueOrEmpty(tenantId));
        out.println("roles=" + valueOrEmpty(roles));
        out.println("profileUpdated=" + config.profilePath());
        out.println("stored=NAVI_BASE_URL,NAVI_ADMIN_TOKEN"
                + (hasText(tenantId) ? ",NAVI_TENANT_ID" : "")
                + (hasText(userId) ? ",NAVI_ADMIN_USER_ID" : "")
                + (hasText(resolvedUsername) ? ",NAVI_ADMIN_USERNAME" : ""));
        return 0;
    }

    private int adminKeyUsage() {
        out.println("Usage: navi upstream admin-key <command> [options]");
        out.println("Commands: inspect, request, status, claim, list, approve, deny, revoke, rotate");
        out.println("  inspect");
        out.println("  request --upstream-system-id <id> --requested-tenant-id <tenantId> [--multi-tenant] --write-profile");
        out.println("  status  [--request-code <code>]");
        out.println("  claim   [--request-code <code>] [--claim-token-env <env>] --write-profile");
        out.println("  approve --request-code <code> --authorized-tenant-ids <tenantId[,tenantId]> [--namespace <prefix>] [--scopes <scope[,scope]>] [--claim-ttl-minutes <minutes|0|-1>] [--credential-expires-at <yyyy-MM-ddTHH:mm:ss>]");
        out.println("  rotate --credential-id <id> --write-profile [--scopes <scope[,scope]>] [--credential-expires-at <yyyy-MM-ddTHH:mm:ss>]");
        out.println("           claim ttl 0 or -1 means the Navigator approver confirms a no-expiry NAVI_ADMIN_API_KEY; the claim window still uses the default TTL.");
        out.println("  deny    --request-code <code> --reason <text>");
        out.println("  revoke  --credential-id <id>");
        out.println("  rotate  --credential-id <id> [--credential-expires-at <yyyy-MM-ddTHH:mm:ss>] --write-profile");
        return 0;
    }

    private int clientAppUsage() {
        out.println("Usage: navi upstream client-app <command> [options]");
        out.println("Commands: list, ensure, ensure-tenant, issue-runtime-key, issue-runtime-credential, issue-control-key");
        out.println("  list [--target-tenant-id <tenantId>]");
        out.println("  ensure --target-tenant-id <tenantId> --upstream-ref <ref> [--name <name>] [--tenant-profile <path>] [--write-profile]");
        out.println("  ensure-tenant --source-system <system> --source-tenant-id <id> --platform-control-profile <path> --tenant-runtime-profile <path> [--rotate-credentials] --write-profile");
        out.println("  issue-runtime-key --client-app-id <id> --tenant-runtime-profile <path> [--rotate-runtime-credential] --write-profile");
        out.println("  issue-control-key --client-app-id <id> [--scopes <scope[,scope]>] --platform-control-profile <path> --write-profile");
        out.println("  These commands require the upstream-admin lane (NAVI_ADMIN_API_KEY); they are not runtime calls.");
        out.println("  Legacy names remain available for one release window. Use `platform tenant ensure`: control and runtime credentials are written to separate private profiles.");
        return 0;
    }

    private int platformUsage() {
        out.println("Usage: navi upstream platform <resource> <command> [options]");
        out.println("Current authority: UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN (NAVI_ADMIN_API_KEY). It is not typed SAAS_PLATFORM authority.");
        out.println("Commands: tenant list/ensure, app list/ensure/issue-control-key/issue-runtime-key, app-scope, agent, model, worker, worker-host, directory, worker-pool (legacy compatibility).");
        out.println("  tenant ensure --source-system <system> --source-tenant-id <id> --platform-control-profile <path> --tenant-runtime-profile <path> [--rotate-credentials] --write-profile");
        out.println("  app issue-control-key --client-app-id <id> --platform-control-profile <path> --write-profile");
        out.println("  app issue-runtime-key --client-app-id <id> --tenant-runtime-profile <path> --write-profile");
        out.println("Both output profiles must be different and gitignored. The control profile stays with the TMS platform; the runtime profile is tenant-only.");
        return 0;
    }

    private int platformAppUsage() {
        out.println("Usage: navi upstream platform app <list|ensure|issue-control-key|issue-runtime-key> [options]");
        out.println("Use `platform app-scope` for system-admin management of one explicit ClientApp.");
        return 0;
    }

    private int platformAppScopeUsage() {
        out.println("Usage: navi upstream platform app-scope <command> --client-app-id <id> [options]");
        out.println("Commands: inspect, agent-list, agent-get, agent-sync, model-grants, model-grant, model-set-default, model-get, model-create, model-update, model-rotate-key, model-clear-key, user-grants, user-grant, user-status, model-bindings, bind-model, unbind-model, set-default-model, workspace-bindings, bind-workspace, unbind-workspace, set-default-workspace, worker-bindings, bind-worker, unbind-worker, set-default-worker, directory-list, directory-init, directory-get, directory-delete, directory-env, directory-files.");
        out.println("Requires only the system-admin profile (NAVI_BASE_URL, NAVI_UPSTREAM_SYSTEM_ID, NAVI_ADMIN_API_KEY). --client-app-id is mandatory and is never read from NAVI_CLIENT_APP_ID.");
        out.println("The server derives tenant from the target ClientApp and fails closed on tenant, upstream-system, namespace, owner and active-status mismatch.");
        return 0;
    }

    private int platformAgentUsage() {
        out.println("Usage: navi upstream platform agent <list|create|get|update|model-bindings|bind-model|unbind-model|set-default-model|workspace-bindings|bind-workspace|unbind-workspace|set-default-workspace|worker-bindings|bind-worker|unbind-worker|set-default-worker> [options]");
        out.println("Uses the current legacy platform lane (NAVI_ADMIN_API_KEY); it is not typed SAAS_PLATFORM authority.");
        return 0;
    }

    private int platformModelUsage() {
        out.println("Usage: navi upstream platform model <list|get|create|update|test|test-saved|rotate-key|clear-key> [options]");
        out.println("Uses the current legacy platform lane (NAVI_ADMIN_API_KEY); it is not typed SAAS_PLATFORM authority.");
        return 0;
    }

    private int appUsage() {
        out.println("Usage: navi upstream app <resource> <command> [options]");
        out.println("ClientApp control lane requires exactly NAVI_CONTROL_API_KEY. Do not load platform-admin, runtime, or typed-management credentials in this profile.");
        out.println("Commands: ensure-grant, agent sync/bindings, model grants/create/update, function import/grant/visible, route list/set/status, directory list/init/get/delete/env/files.");
        out.println("Use a dedicated gitignored platform-control profile created by `platform tenant ensure` or `platform app issue-control-key`.");
        return 0;
    }

    private int runtimeUsage() {
        out.println("Usage: navi upstream runtime <token|audit|binding-audit|task-audit|task-completion-readiness|termination-readiness|task-terminate|task-reconcile|task-terminal-cleanup-repair|readiness|owner-smoke|inspect|ask|safe-ask|messages|diagnostics|evidence|sessions|session-messages|skill|account-context> [options]");
        out.println("Runtime lane accepts only ClientApp runtime material (key/secret or access token) and rejects admin, control, and typed-management credentials.");
        out.println("Use a tenant-runtime profile created by `platform tenant ensure` or `platform app issue-runtime-key`; runtime access tokens are not persisted by provisioning.");
        out.println("  audit --request-id <clientRequestId> [--operation runtime-token|safe-ask|ask|task-terminate|task-reconcile|task-terminal-cleanup-repair] [--json]");
        out.println("  audit --since <ISO-8601 offset time> --until <ISO-8601 offset time> [--operation runtime-token|safe-ask|ask|task-terminate|task-reconcile|task-terminal-cleanup-repair] [--agent-code <id>] [--upstream-user-id <id>] [--limit <1..100>] [--json]");
        out.println("    STANDARD ask audit is task-id independent; Java SDK requests expose clientRequestId and parentClientRequestId correlation.");
        out.println("    Audit timestamps are RFC 3339 UTC instants; readiness reports serverTimezone, auditStorageTimezone, and taskIdDateTimezone.");
        out.println("  termination-readiness --task-id <id> --expected-physical-worker-id <id> [--json]");
        out.println("  task-completion-readiness --task-id <id> --expected-physical-worker-id <id> [--json]");
        out.println("  task-terminate --task-id <id> --expected-physical-worker-id <id> --reason <code> [--dry-run | --confirm-task-id <id>] [--replay-client-request-id <id>] [--json]");
        out.println("  task-reconcile --task-id <id> --replay-client-request-id <originalTerminationRequestId> [--json]");
        out.println("    read-only: the JSON body contains only taskId; use the original termination request id exactly once in the header.");
        out.println("  task-terminal-cleanup-repair --task-id <id> --expected-physical-worker-id <id> [--dry-run | --confirm-task-id <id>] [--replay-client-request-id <dryRunRequestId>] [--json]");
        out.println("    run dry-run first, then confirm with the exact printed request id; the CLI never retries repair automatically.");
        out.println("Audit uses ClientApp key/secret only, derives tenant/system/ClientApp on the server, issues no token, and creates no task/context/session or runtime dispatch.");
        return 0;
    }

    private void printLegacyMigrationNotice(String command) {
        if ("client-app ensure-tenant".equals(command)) {
            out.println("migrationNotice=client-app ensure-tenant is a legacy alias; use platform tenant ensure with --platform-control-profile and --tenant-runtime-profile");
        } else if (command.startsWith("agent system-") || command.startsWith("model system-")) {
            out.println("migrationNotice=system-* is a legacy alias; use platform agent or platform model");
        } else if (Set.of("runtime-token", "owner-smoke", "verify-agent-readiness", "verify-agent-grant", "ask", "messages").contains(command)) {
            out.println("migrationNotice=top-level runtime command is a legacy alias; use runtime "
                    + switch (command) {
                        case "runtime-token" -> "token";
                        case "owner-smoke" -> "owner-smoke";
                        case "verify-agent-readiness", "verify-agent-grant" -> "readiness";
                        default -> command;
                    });
        }
    }

    private int agentUsage() {
        out.println("Usage: navi upstream agent <command> [options]");
        out.println("Commands: sync, model-bindings, bind-model, unbind-model, set-default-model, workspace-bindings, bind-workspace, unbind-workspace, set-default-workspace, worker-bindings, bind-worker, unbind-worker, set-default-worker, system-list, system-create, system-get, system-update, system-model-bindings, system-bind-model, system-unbind-model, system-set-default-model, system-workspace-bindings, system-bind-workspace, system-unbind-workspace, system-set-default-workspace, system-worker-bindings, system-bind-worker, system-unbind-worker, system-set-default-worker");
        out.println("  sync --manifest <json> --client-app-id <id>");
        out.println("  bind-model|set-default-model --client-app-id <id> --agent-code <id> --model-config-id <id>");
        out.println("  bind-workspace|set-default-workspace --client-app-id <id> --agent-code <id> --directory-id <id>");
        out.println("  bind-worker|set-default-worker --client-app-id <id> --agent-code <id> --worker-pool-id <id>");
        out.println("  system-create|system-update --file <json> [--target-tenant-id <tenantId>]");
        out.println("ClientApp agent sync/bind commands use NAVI_CONTROL_API_KEY. System agent commands use NAVI_ADMIN_API_KEY.");
        return 0;
    }

    private int modelUsage() {
        out.println("Usage: navi upstream model <command> [options]");
        out.println("Commands: grants, grant, set-default, create, update, test, test-saved, rotate-key, clear-key, system-list, system-get, system-create, system-update, system-test, system-test-saved, system-rotate-key, system-clear-key");
        out.println("  grants --client-app-id <id>");
        out.println("  grant --client-app-id <id> --model-config-id <id> [--set-default] [--write-profile]");
        out.println("  create --client-app-id <id> --name <name> --model-name <name> [--worker-backend <backend>] [--model-base-url <url>] [--api-key-env <env>] [--available-models <csv>] [--set-default]");
        out.println("  update --client-app-id <id> --model-config-id <id> [--name <name>] [--model-name <name>] [--worker-backend <backend>] [--model-base-url <url>] [--available-models <csv>] [--set-default]");
        out.println("  test --client-app-id <id> --worker-backend <backend> --model-name <name> [--worker-id <id>] [--model-base-url <url>] [--api-key-env <env>]");
        out.println("  test-saved --client-app-id <id> --model-config-id <id> [--worker-id <id>]");
        out.println("  rotate-key --client-app-id <id> --model-config-id <id> --api-key-env <env>");
        out.println("  system-list [--target-tenant-id <tenantId>]");
        out.println("  system-get --model-config-id <id> [--target-tenant-id <tenantId>]");
        out.println("  system-create --name <name> --model-name <name> [--worker-backend <backend>] [--model-base-url <url>] [--api-key-env <env>] [--available-models <csv>] [--target-tenant-id <tenantId>]");
        out.println("  system-update --model-config-id <id> [--name <name>] [--model-name <name>] [--worker-backend <backend>] [--model-base-url <url>] [--available-models <csv>] [--target-tenant-id <tenantId>]");
        out.println("  system-test --worker-backend <backend> --model-name <name> [--worker-id <id>] [--model-base-url <url>] [--api-key-env <env>] [--target-tenant-id <tenantId>]");
        out.println("  system-test-saved --model-config-id <id> [--worker-id <id>] [--target-tenant-id <tenantId>]");
        out.println("Backends: LANGGRAPH_BIZ, OPENAI_CODEX, OPENAI_CODEX_APP_SERVER, CLAUDE_CODE, GEMINI_CLI.");
        out.println("OPENAI_CODEX and OPENAI_CODEX_APP_SERVER subscription configs omit --model-base-url and --api-key-env.");
        out.println("GPT-5.6 aliases include codex-latest/terra/luna/fast/deep/xhigh/max; codex-ultra requires OPENAI_CODEX_APP_SERVER.");
        out.println("ClientApp model create/update/grant/default commands use NAVI_CONTROL_API_KEY. System model commands use NAVI_ADMIN_API_KEY.");
        return 0;
    }

    private int authWhoami(CliArguments args) {
        if (args.flag("help")) {
            return authWhoamiUsage();
        }
        Map<String, Object> response = typedManagementAuthApi().whoami();
        printTypedManagementIdentity("whoami", response);
        return 0;
    }

    private int inspectPermissions(CliArguments args) {
        if (args.flag("help")) {
            return inspectPermissionsUsage();
        }
        if (args.flag("explain-auth")) {
            return inspectPermissionsExplain(args);
        }
        Map<String, Object> response = typedManagementAuthApi().permissions();
        printTypedManagementIdentity("permissions", response);
        return 0;
    }

    private int authWhoamiUsage() {
        out.println("Usage: navi upstream auth whoami [--principal-credential-env <envName>]");
        out.println("Read-only typed-management inspection. It requires exactly one NAVI_PRINCIPAL_CREDENTIAL or explicit source.");
        out.println("It sends only X-Navi-Principal-Credential and never falls back to NAVI_ADMIN_API_KEY, control, runtime, user, task, or Worker credentials.");
        out.println("The server is authoritative; local profile metadata and config check do not authorize a mutation.");
        return 0;
    }

    private int inspectPermissionsUsage() {
        out.println("Usage: navi upstream inspect permissions [--explain-auth --route-id <id> --action-id <id>");
        out.println("  [--target-reference <safe-ref> --impact-reference <safe-ref> --reason-reference <safe-ref>]]");
        out.println("Read-only typed-management inspection. It requires exactly one NAVI_PRINCIPAL_CREDENTIAL or explicit source.");
        out.println("--explain-auth accepts only a registered typed-management route/action; the three safe references are all-or-none.");
        out.println("Explain is non-binding and every mutation must be re-authorized by the server for exact owner, grant, tenant, ClientApp, action, and credential facts.");
        return 0;
    }

    private int inspectPermissionsExplain(CliArguments args) {
        String routeId = requiredOption(args, "route-id", "typed-management route id");
        String actionId = requiredOption(args, "action-id", "typed-management action id");
        if (!TypedManagementExplainCatalog.load().matches(routeId, actionId)) {
            throw new UpstreamCliException("typed-management explain route/action is not registered "
                    + "(TYPED_MANAGEMENT_EXPLAIN_ROUTE_ACTION_UNREGISTERED)");
        }

        String targetReference = args.option("target-reference");
        String impactReference = args.option("impact-reference");
        String reasonReference = args.option("reason-reference");
        boolean anyReference = hasText(targetReference) || hasText(impactReference) || hasText(reasonReference);
        boolean allReferences = hasText(targetReference) && hasText(impactReference) && hasText(reasonReference);
        if (anyReference && !allReferences) {
            throw new UpstreamCliException("typed-management explain references must be supplied together "
                    + "(TYPED_MANAGEMENT_EXPLAIN_REFERENCE_SET_INCOMPLETE)");
        }
        if (allReferences) {
            validateManagementReference(targetReference);
            validateManagementReference(impactReference);
            validateManagementReference(reasonReference);
        }

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("routeId", routeId);
        form.put("actionId", actionId);
        if (allReferences) {
            form.put("targetReference", targetReference);
            form.put("impactReference", impactReference);
            form.put("reasonReference", reasonReference);
        }
        Map<String, Object> response = typedManagementAuthApi().explain(form);
        if (!Boolean.TRUE.equals(response.get("nonBinding"))) {
            throw new UpstreamCliException("typed-management explain response is not non-binding "
                    + "(TYPED_MANAGEMENT_EXPLAIN_NON_BINDING_REQUIRED)");
        }

        out.println("preflight=PREFLIGHT");
        out.println("nonBinding=true");
        out.println("routeId=" + routeId);
        out.println("actionId=" + actionId);
        out.println("allowed=" + Boolean.TRUE.equals(response.get("allowed")));
        out.println("reasonCode=" + safeTypedResponseValue(response, "reasonCode"));
        out.println("targetOwnerGrantTenant=UNRESOLVED_SERVER_SIDE");
        out.println("mutationAuthorization=REAUTHORIZE_ON_SERVER");
        return 0;
    }

    private int workerUsage() {
        out.println("Usage: navi upstream worker <command> [options]");
        out.println("Commands: list, create, get, update, delete, health, processes, kill");
        out.println("  list [--target-tenant-id <tenantId>]");
        out.println("  create --file <json> [--target-tenant-id <tenantId>] [--write-profile]");
        out.println("  get|delete|health|processes --worker-id <id>");
        out.println("  update --worker-id <id> --file <json>");
        out.println("  kill --worker-id <id> --pid <pid> [--force]");
        return 0;
    }

    private int workerHostUsage() {
        out.println("Usage: navi upstream worker-host <command> [options]");
        out.println("Commands: apply, update, verify, install");
        out.println("  apply  --file <json> [--target-tenant-id <tenantId>] [--worker-id <claudeWorkerId>] [--write-profile]");
        out.println("  update --file <json> [--worker-id <claudeWorkerId>] [--write-profile]");
        out.println("  verify --file <json>");
        out.println("  install --file <json> [--install-shell auto|powershell|bash|wsl] [--wsl-user <user>] [--wsl-distro <name>] [--timeout-seconds <seconds>] [--no-start] [--dry-run]");
        out.println("WorkerHost is the normal upstream bootstrap entry; worker and worker-pool commands remain low-level compatibility commands.");
        out.println("apply creates a Physical Worker only when no worker id is supplied; use it only for a new WorkerHost.");
        out.println("update is for an existing Physical Worker only; it requires --worker-id or NAVI_WORKER_ID and cannot create one.");
        out.println("Codex is Navi-routed through claudeCode.codexConfig; workers.codex.workerId and direct OPENAI_CODEX/OPENAI_CODEX_APP_SERVER identities are unsupported.");
        return 0;
    }

    private int directoryUsage() {
        out.println("Usage: navi upstream directory <command> [options]");
        out.println("Commands: list, init, get, delete, env, files, client-list, client-init, client-get, client-delete, client-env, client-files");
        out.println("  list [--target-tenant-id <tenantId>] [--worker-id <id>]");
        out.println("  init --file <json> [--write-profile]");
        out.println("  get|delete --directory-id <id>");
        out.println("  env|files --directory-id <id> --file <json>");
        out.println("  client-list [--client-app-id <id>] [--worker-id <id>] [--workspace-scope <scope>] [--upstream-user-id <id>]");
        out.println("  client-init [--client-app-id <id>] --file <json> [--write-profile]");
        out.println("  client-get|client-delete [--client-app-id <id>] --directory-id <id>");
        out.println("  client-env|client-files [--client-app-id <id>] --directory-id <id> --file <json>");
        return 0;
    }

    private int workerPoolUsage() {
        out.println("Usage: navi upstream worker-pool <command> [options]");
        out.println("Legacy internal compatibility commands. WorkerPool is a Navigator routing artifact, not for OPENAI_CODEX or OPENAI_CODEX_APP_SERVER.");
        out.println("Use worker-host verify then update for an existing Physical Worker; apply only for a new WorkerHost.");
        out.println("Commands: list, create, register-worker, add-member, status");
        out.println("  list [--target-tenant-id <tenantId>]");
        out.println("  create --file <json> [--target-tenant-id <tenantId>] [--write-profile]");
        out.println("  register-worker --file <json> [--write-profile]");
        out.println("  add-member --pool-id <id> [--worker-id <workerId>] [--target-tenant-id <tenantId>]");
        out.println("  status --pool-id <id> --status ENABLED|DISABLED [--target-tenant-id <tenantId>]");
        return 0;
    }

    private int functionUsage() {
        out.println("Usage: navi upstream function <command> [options]");
        out.println("Commands: import, grant, grant-status, visible");
        out.println("  import       --manifest <path>");
        out.println("  grant        --function-id <id> [--version <version>] [--status ENABLED|DISABLED]");
        out.println("  grant-status --grant-id <id> --status ENABLED|DISABLED");
        out.println("  visible      [--client-app-id <clientAppId>]");
        return 0;
    }

    private int routeUsage() {
        out.println("Usage: navi upstream route <command> [options]");
        out.println("Commands: list, set, status");
        out.println("  list   [--client-app-id <clientAppId>]");
        out.println("  set    --upstream-ref <ref> --url <baseUrl> [--user-token-header <header>] [--status ENABLED|DISABLED]");
        out.println("  status --upstream-ref <ref> --status ENABLED|DISABLED");
        return 0;
    }

    private int diagnosticsUsage() {
        out.println("Usage: navi upstream diagnostics <command|options> [options]");
        out.println("Commands: session-dir");
        out.println("  diagnostics --task-id <taskId> --agent-code <agentId> [--upstream-user-id <id>]");
        out.println("  diagnostics session-dir --context-id <contextId> [--task-id <taskId>] [--provider-task-id <providerTaskId>] [--worker-backend LANGGRAPH_BIZ|OPENAI_CODEX] [--data-root <bizWorkerDataRoot>] [--biz-worker-env-file <path>] [--codex-workspace-root <path>]");
        out.println("    LANGGRAPH_BIZ resolves the local LangGraph BizWorker runtime session path from a bctx_yyyyMMdd_<shard>_<id> contextId.");
        out.println("    OPENAI_CODEX resolves the Codex navigator_business MCP debug log; pass --provider-task-id for the Codex worker task UUID when available.");
        out.println("    It prints paths and worker hints only; it does not print tokens, headers, credentials, or log contents.");
        return 0;
    }

    private int configCheckUsage() {
        out.println("Usage: navi upstream config check [--profile <path>]");
        out.println("Reports local configuration state only. Help never loads or validates a profile.");
        return 0;
    }

    private int runtimeTokenUsage() {
        out.println("Usage: navi upstream runtime token [options]");
        out.println("Exchanges ClientApp key/secret for a runtime access token; help never loads a profile or issues a token.");
        return 0;
    }

    private int runtimeReadinessUsage() {
        out.println("Usage: navi upstream runtime readiness --upstream-user-id <id> [--agent-code <id>] [options]");
        out.println("Reads agent runtime readiness; help never loads a profile or sends a request.");
        return 0;
    }

    private int runtimeOwnerSmokeUsage() {
        out.println("Usage: navi upstream runtime owner-smoke --upstream-user-id <id> [--agent-code <id>] [options]");
        out.println("Runs owner readiness checks without model submission; help never loads a profile or sends a request.");
        return 0;
    }

    private int configCheck() {
        UpstreamCliConfig.LocalState configState = config.configState();
        out.println("configState=" + configState);
        out.println("profileSafety=" + config.profileSafetyState());
        out.println("typedMetadata=" + config.typedMetadataState());
        out.println("typedCredentialSource=" + config.typedCredentialSourceState());
        out.println("legacyPlatformLane=" + config.legacyPlatformLaneAvailability());
        out.println("clientAppControlLane=" + config.clientAppControlLaneAvailability());
        out.println("runtimeLane=" + config.runtimeLaneAvailability());
        out.println("typedManagementAuthority=" + config.typedManagementAuthorityAvailability());
        out.println("authorization=UNVERIFIED");
        return configState == UpstreamCliConfig.LocalState.INVALID ? 2 : 0;
    }

    private int runtimeToken(CliArguments args) {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        String clientRequestId = beginRuntimeClientRequest("runtime-token", null, null);
        ClientAppRuntimeAccessTokenDTO token;
        try {
            token = exchangeRuntimeAccessToken(args);
        } catch (RuntimeRequestFailure e) {
            throw e;
        } catch (NavigatorApiException e) {
            throw runtimeRequestFailure(e, "RUNTIME_TOKEN_RESPONSE_NOT_RECEIVED", clientRequestId);
        } catch (RuntimeException e) {
            throw new RuntimeRequestFailure(
                    "sanitizedErrorCode=RUNTIME_TOKEN_CLIENT_FAILURE clientRequestId=" + clientRequestId);
        }
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
        printRuntimeTokenExpiry(token);
        return 0;
    }

    private int adminKeyRequest(CliArguments args) {
        if (!args.flag("write-profile")) {
            throw new UpstreamCliException("admin-key request requires --write-profile to store the one-time claim token without printing it");
        }
        config.assertProfileWritable();

        CreateUpstreamBootstrapRequestForm form = new CreateUpstreamBootstrapRequestForm();
        form.setUpstreamSystemId(requiredOptionOrConfig(args, "upstream-system-id", "NAVI_UPSTREAM_SYSTEM_ID", "upstream system id"));
        form.setRequestedTenantId(requiredOptionOrConfig(args, "requested-tenant-id", "NAVI_REQUESTED_TENANT_ID", "requested tenant id"));
        form.setMultiTenant(args.flag("multi-tenant") || Boolean.parseBoolean(config.get("NAVI_UPSTREAM_MULTI_TENANT")));
        form.setReason(args.option("reason"));
        form.setApplicantLabel(args.option("applicant-label"));

        UpstreamBootstrapRequestCreatedDTO created = bootstrapApi().requestUpstreamAdminKey(form);
        if (created == null || !hasText(created.getRequestCode()) || !hasText(created.getClaimToken())) {
            throw new UpstreamCliException("admin-key request response did not include requestCode and claimToken");
        }

        config.writeProfileValue("NAVI_ADMIN_KEY_REQUEST_CODE", created.getRequestCode());
        config.writeProfileValue("NAVI_ADMIN_KEY_CLAIM_TOKEN", created.getClaimToken());
        config.writeProfileValue("NAVI_BASE_URL", config.required("NAVI_BASE_URL", "Navigator base URL"));
        config.writeProfileValue("NAVI_UPSTREAM_SYSTEM_ID", form.getUpstreamSystemId());
        config.writeProfileValue("NAVI_REQUESTED_TENANT_ID", form.getRequestedTenantId());
        config.writeProfileValue("NAVI_UPSTREAM_MULTI_TENANT", String.valueOf(Boolean.TRUE.equals(form.getMultiTenant())));

        out.println("admin-key request ok");
        out.println("profileUpdated=" + config.profilePath());
        out.println("stored=NAVI_ADMIN_KEY_REQUEST_CODE,NAVI_ADMIN_KEY_CLAIM_TOKEN");
        out.println("requestCode=" + valueOrEmpty(created.getRequestCode()));
        out.println("requestCodeSuffix=" + valueOrEmpty(created.getRequestCodeSuffix()));
        out.println("claimToken=" + SecretMasker.mask(created.getClaimToken()));
        out.println("status=" + valueOrEmpty(created.getStatus()));
        out.println("requestExpiresAt=" + valueOrEmpty(created.getRequestExpiresAt()));
        return 0;
    }

    private int adminKeyStatus(CliArguments args) {
        UpstreamBootstrapRequestDTO request = bootstrapApi()
                .getUpstreamAdminKeyRequestStatus(adminKeyRequestCode(args));
        out.println("admin-key status ok");
        printUpstreamBootstrapRequest("request", request);
        return 0;
    }

    private int adminKeyInspect(CliArguments args) {
        UpstreamAdminCredentialDTO credential = upstreamAdminApi().inspectCurrentUpstreamAdminCredential(
                optionalOptionOrConfig(args, "admin-api-key", "NAVI_ADMIN_API_KEY"));
        out.println("admin-key inspect ok");
        printUpstreamAdminCredential("credential", credential);
        out.println("rotation=use admin-key rotate --credential-id "
                + valueOrEmpty(credential != null ? credential.getCredentialId() : null)
                + " to replace this same upstream admin principal");
        return 0;
    }

    private int adminKeyClaim(CliArguments args) {
        if (!args.flag("write-profile")) {
            throw new UpstreamCliException("admin-key claim requires --write-profile to store NAVI_ADMIN_API_KEY without printing it");
        }
        config.assertProfileWritable();

        ClaimUpstreamAdminCredentialForm form = new ClaimUpstreamAdminCredentialForm();
        form.setClaimToken(config.required("NAVI_ADMIN_KEY_CLAIM_TOKEN", "admin key claim token"));

        UpstreamAdminCredentialClaimDTO claim = bootstrapApi()
                .claimUpstreamAdminKey(adminKeyRequestCode(args), form);
        if (claim == null || !hasText(claim.getNaviAdminApiKey())) {
            throw new UpstreamCliException("admin-key claim response did not include NAVI_ADMIN_API_KEY");
        }

        config.writeProfileValue("NAVI_ADMIN_API_KEY", claim.getNaviAdminApiKey());
        config.writeProfileValue("NAVI_ADMIN_KEY_CLAIM_TOKEN", "");

        out.println("admin-key claim ok");
        out.println("profileUpdated=" + config.profilePath());
        out.println("stored=NAVI_ADMIN_API_KEY");
        out.println("credentialId=" + valueOrEmpty(claim.getCredentialId()));
        out.println("naviAdminApiKey=" + SecretMasker.mask(claim.getNaviAdminApiKey()));
        out.println("upstreamSystemId=" + valueOrEmpty(claim.getUpstreamSystemId()));
        out.println("authorizedTenantIds=" + joinList(claim.getAuthorizedTenantIds()));
        out.println("authorizedClientAppNamespace=" + valueOrEmpty(claim.getAuthorizedClientAppNamespace()));
        out.println("scopes=" + joinList(claim.getScopes()));
        out.println("expiresAt=" + valueOrEmpty(claim.getExpiresAt()));
        return 0;
    }

    private int adminKeyList(CliArguments args) {
        List<UpstreamBootstrapRequestDTO> requests = operatorOrAdminApi()
                .listUpstreamBootstrapRequests(args.option("status"));
        out.println("admin-key list ok");
        out.println("requestCount=" + (requests == null ? 0 : requests.size()));
        if (requests != null) {
            for (UpstreamBootstrapRequestDTO request : requests) {
                printUpstreamBootstrapRequest("request", request);
            }
        }
        return 0;
    }

    private int adminKeyApprove(CliArguments args) {
        ApproveUpstreamBootstrapRequestForm form = new ApproveUpstreamBootstrapRequestForm();
        String tenantIds = args.option("authorized-tenant-ids");
        if (!hasText(tenantIds)) {
            tenantIds = args.option("authorized-tenant-id");
        }
        form.setAuthorizedTenantIds(parseCsv(requiredValue(tenantIds, "authorized tenant ids are required (--authorized-tenant-ids)")));
        String namespace = args.option("namespace");
        if (!hasText(namespace)) {
            namespace = args.option("authorized-client-app-namespace");
        }
        form.setAuthorizedClientAppNamespace(namespace);
        form.setScopes(parseCsv(args.option("scopes")));
        form.setClaimTtlMinutes(parseLongOption(args.option("claim-ttl-minutes"), "claim ttl minutes"));
        form.setCredentialExpiresAt(parseLocalDateTimeOption(args.option("credential-expires-at"), "credential expires at"));

        UpstreamBootstrapRequestDTO request = operatorOrAdminApi()
                .approveUpstreamBootstrapRequest(adminKeyRequestCode(args), form);
        out.println("admin-key approve ok");
        printUpstreamBootstrapRequest("request", request);
        return 0;
    }

    private int adminKeyDeny(CliArguments args) {
        DenyUpstreamBootstrapRequestForm form = new DenyUpstreamBootstrapRequestForm();
        form.setDeniedReason(requiredOption(args, "reason", "denied reason"));

        UpstreamBootstrapRequestDTO request = operatorOrAdminApi()
                .denyUpstreamBootstrapRequest(adminKeyRequestCode(args), form);
        out.println("admin-key deny ok");
        printUpstreamBootstrapRequest("request", request);
        return 0;
    }

    private int adminKeyRevoke(CliArguments args) {
        UpstreamAdminCredentialDTO credential = operatorOrAdminApi()
                .revokeUpstreamAdminCredential(requiredOption(args, "credential-id", "credential id"));
        out.println("admin-key revoke ok");
        printUpstreamAdminCredential("credential", credential);
        return 0;
    }

    private int adminKeyRotate(CliArguments args) {
        if (!args.flag("write-profile")) {
            throw new UpstreamCliException("admin-key rotate requires --write-profile to store the new NAVI_ADMIN_API_KEY without printing it");
        }
        config.assertProfileWritable();

        RotateUpstreamAdminCredentialForm form = new RotateUpstreamAdminCredentialForm();
        form.setCredentialExpiresAt(parseLocalDateTimeOption(args.option("credential-expires-at"), "credential expires at"));
        form.setScopes(parseCsv(args.option("scopes")));
        UpstreamAdminCredentialClaimDTO claim = operatorOrAdminApi()
                .rotateUpstreamAdminCredential(requiredOption(args, "credential-id", "credential id"), form);
        if (claim == null || !hasText(claim.getNaviAdminApiKey())) {
            throw new UpstreamCliException("admin-key rotate response did not include NAVI_ADMIN_API_KEY");
        }

        config.writeProfileValue("NAVI_ADMIN_API_KEY", claim.getNaviAdminApiKey());

        out.println("admin-key rotate ok");
        out.println("profileUpdated=" + config.profilePath());
        out.println("stored=NAVI_ADMIN_API_KEY");
        out.println("credentialId=" + valueOrEmpty(claim.getCredentialId()));
        out.println("naviAdminApiKey=" + SecretMasker.mask(claim.getNaviAdminApiKey()));
        out.println("upstreamSystemId=" + valueOrEmpty(claim.getUpstreamSystemId()));
        out.println("authorizedTenantIds=" + joinList(claim.getAuthorizedTenantIds()));
        out.println("authorizedClientAppNamespace=" + valueOrEmpty(claim.getAuthorizedClientAppNamespace()));
        out.println("scopes=" + joinList(claim.getScopes()));
        out.println("expiresAt=" + valueOrEmpty(claim.getExpiresAt()));
        return 0;
    }

    private int upstreamClientAppList(CliArguments args) {
        String tenantId = optionalOptionOrConfig(args, "target-tenant-id", "NAVI_TARGET_TENANT_ID");
        List<ClientAppDTO> apps = upstreamAdminApi().listUpstreamManagedClientApps(tenantId);
        if ("platform tenant list".equals(args.command())) {
            out.println("migrationNotice=platform tenant list returns ClientApp records; use platform app list");
            out.println("resourceType=CLIENT_APP");
        }
        out.println("client-app list ok");
        out.println("clientAppCount=" + (apps == null ? 0 : apps.size()));
        if (apps != null) {
            for (ClientAppDTO app : apps) {
                printClientApp("clientApp", app);
            }
        }
        return 0;
    }

    private int upstreamClientAppEnsure(CliArguments args) {
        EnsureUpstreamClientAppForm form = new EnsureUpstreamClientAppForm();
        form.setTargetTenantId(requiredOptionOrConfig(args, "target-tenant-id", "NAVI_TARGET_TENANT_ID", "target tenant id"));
        form.setUpstreamRef(requiredOptionOrConfig(args, "upstream-ref", "NAVI_UPSTREAM_REF", "upstream ref"));
        form.setName(args.option("name"));
        form.setDescription(args.option("description"));
        form.setOwnerUserId(args.option("owner-user-id"));
        form.setCapabilityDomain(args.option("capability-domain"));

        ClientAppDTO app = upstreamAdminApi().ensureUpstreamClientApp(form);
        if (app == null || !hasText(app.getClientAppId())) {
            throw new UpstreamCliException("client-app ensure response did not include clientAppId");
        }

        Path targetProfile = tenantProfilePath(args);
        if (args.flag("write-profile")) {
            config.assertProfileWritable(targetProfile);
            config.writeProfileValue(targetProfile, "NAVI_BASE_URL", config.required("NAVI_BASE_URL", "Navigator base URL"));
            config.writeProfileValue(targetProfile, "NAVI_TENANT_ID", emptyIfNull(app.getTenantId()));
            config.writeProfileValue(targetProfile, "NAVI_CLIENT_APP_ID", emptyIfNull(app.getClientAppId()));
            config.writeProfileValue(targetProfile, "NAVI_UPSTREAM_SYSTEM_ID", emptyIfNull(app.getUpstreamSystemId()));
            config.writeProfileValue(targetProfile, "NAVI_UPSTREAM_REF", emptyIfNull(app.getUpstreamRef()));
        }

        out.println("client-app ensure ok");
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + targetProfile);
            out.println("stored=NAVI_CLIENT_APP_ID");
        }
        printClientApp("clientApp", app);
        return 0;
    }

    private int upstreamTenantClientAppEnsure(CliArguments args, boolean legacyAlias) {
        if (!args.flag("write-profile")) {
            throw new UpstreamCliException("platform tenant ensure requires --write-profile to store one-time credentials without printing them");
        }
        ProvisionedProfileTargets profiles = provisionedProfileTargets(args, legacyAlias);

        String sourceSystem = requiredOptionOrConfig(args, "source-system", "NAVI_UPSTREAM_SYSTEM_ID", "source system");
        String sourceTenantId = sourceTenantId(args);
        EnsureUpstreamTenantClientAppForm form = new EnsureUpstreamTenantClientAppForm();
        form.setSourceSystem(sourceSystem);
        form.setSourceTenantId(sourceTenantId);
        form.setClientAppName(args.option("name"));
        form.setCapabilityDomain(args.option("capability-domain"));
        form.setTenantName(args.option("tenant-name"));
        form.setUpstreamRef(optionalOptionOrConfig(args, "upstream-ref", "NAVI_UPSTREAM_REF"));
        form.setAgentRole(args.option("agent-role"));
        form.setAgentBundleCode(args.option("agent-bundle-code"));
        form.setAgentCode(optionalOptionOrConfig(args, "agent-code", "NAVI_AGENT_CODE"));
        form.setModelProfileCode(optionalOptionOrConfig(args, "model-profile-code", "NAVI_MODEL_PROFILE_CODE"));
        form.setModelConfigId(optionalOptionOrConfig(args, "model-config-id", "NAVI_MODEL_CONFIG_ID"));
        form.setSkillId(optionalOptionOrConfig(args, "skill-id", "NAVI_SKILL_ID"));
        form.setWorkerPoolId(optionalOptionOrConfig(args, "worker-pool-id", "NAVI_WORKER_POOL_ID"));
        form.setWorkerBackend(optionalOptionOrConfig(args, "worker-backend", "NAVI_WORKER_BACKEND"));
        form.setPhysicalWorkerId(firstNonBlank(
                optionalOptionOrConfig(args, "physical-worker-id", "NAVI_PHYSICAL_WORKER_ID"),
                optionalOptionOrConfig(args, "worker-id", "NAVI_BIZ_WORKER_ID")));
        form.setDirectoryId(optionalOptionOrConfig(args, "directory-id", "NAVI_DIRECTORY_ID"));
        form.setBizWorkerBaseUrl(optionalOptionOrConfig(args, "biz-worker-base-url", "NAVI_BIZ_WORKER_BASE_URL"));
        form.setRotateCredentials(args.flag("rotate-credentials"));

        UpstreamTenantClientAppProvisioningDTO dto = upstreamAdminApi().ensureUpstreamTenantClientApp(form);
        if (dto == null || !hasText(dto.getClientAppId())) {
            throw new UpstreamCliException("client-app ensure-tenant response did not include clientAppId");
        }
        if (isCredentialsNotReplayable(dto)) {
            throw new UpstreamCliException("client-app ensure-tenant returned CREDENTIALS_NOT_REPLAYABLE; rerun with --rotate-credentials to issue new one-time credentials");
        }
        if (!hasText(dto.getClientAppKey()) || !hasText(dto.getClientAppSecret()) || !hasText(dto.getControlApiKey())) {
            throw new UpstreamCliException("client-app ensure-tenant response did not include full binding secrets; rerun with --rotate-credentials to issue new one-time credentials");
        }

        writeProvisionedTenantProfiles(profiles, dto, sourceSystem, sourceTenantId);

        out.println(legacyAlias ? "client-app ensure-tenant ok" : "platform tenant ensure ok");
        out.println("platformControlProfileUpdated=" + profiles.platformControlProfile());
        out.println("tenantRuntimeProfileUpdated=" + profiles.tenantRuntimeProfile());
        out.println("platformControlStored=" + provisionedControlStoredKeys(dto));
        out.println("tenantRuntimeStored=" + provisionedRuntimeStoredKeys(dto));
        printUpstreamTenantClientAppProvisioning(dto);
        return 0;
    }

    private int runtimeAudit(CliArguments args) throws Exception {
        String requestId = args.option("request-id");
        String since = args.option("since");
        String until = args.option("until");
        if (hasText(requestId) == (hasText(since) || hasText(until))) {
            throw new UpstreamCliException(
                    "runtime audit requires exactly --request-id or both --since and --until");
        }
        if (!hasText(requestId) && (!hasText(since) || !hasText(until))) {
            throw new UpstreamCliException("runtime audit requires both --since and --until");
        }
        Integer limit = parseInteger(args.option("limit"));
        String appKey = clientAppKey(args);
        String appSecret = config.required("NAVI_CLIENT_APP_SECRET", "client app secret");
        RuntimeRequestAuditPageDTO page;
        try {
            page = new BusinessAgentApi(runtimeAuditHttp()).queryRuntimeAudits(
                    appKey,
                    appSecret,
                    requestId,
                    since,
                    until,
                    args.option("operation"),
                    args.option("agent-code"),
                    args.option("upstream-user-id"),
                    limit);
        } catch (NavigatorApiException e) {
            throw runtimeRequestFailure(e, "RUNTIME_AUDIT_RESPONSE_NOT_RECEIVED", requestId);
        }
        if (page == null) {
            throw new RuntimeRequestFailure(
                    "sanitizedErrorCode=RUNTIME_AUDIT_EMPTY_RESPONSE clientRequestId=" + valueOrNull(requestId));
        }
        if (args.flag("json")) {
            printJson(page);
        } else {
            printRuntimeAudits(page);
        }
        return 0;
    }

    private int runtimeBindingAudit(CliArguments args) throws Exception {
        String appKey = clientAppKey(args);
        String appSecret = config.required("NAVI_CLIENT_APP_SECRET", "client app secret");
        String agentCode = requiredOptionOrConfig(args, "agent-code", "NAVI_AGENT_CODE", "agent code");
        String upstreamUserId = upstreamUserId(args);
        String modelConfigId = requiredOptionOrConfig(
                args, "model-config-id", "NAVI_MODEL_CONFIG_ID", "model config id");
        String directoryId = requiredOptionOrConfig(
                args, "directory-id", "NAVI_DIRECTORY_ID", "directory id");
        RuntimeBindingAuditDTO audit;
        try {
            audit = new BusinessAgentApi(runtimeAuditHttp()).auditRuntimeBinding(
                    appKey,
                    appSecret,
                    agentCode,
                    upstreamUserId,
                    modelConfigId,
                    directoryId);
        } catch (NavigatorApiException e) {
            throw runtimeStateAuditFailure(e, "RUNTIME_BINDING_AUDIT_QUERY_FAILED");
        }
        if (audit == null) {
            throw new RuntimeRequestFailure("sanitizedErrorCode=RUNTIME_BINDING_AUDIT_EMPTY_RESPONSE");
        }
        if (args.flag("json")) {
            printJson(audit);
        } else {
            printRuntimeBindingAudit(audit);
        }
        return 0;
    }

    private int runtimeTaskAudit(CliArguments args) throws Exception {
        String appKey = clientAppKey(args);
        String appSecret = config.required("NAVI_CLIENT_APP_SECRET", "client app secret");
        String upstreamUserId = upstreamUserId(args);
        String taskId = requiredOption(args, "task-id", "task id");
        RuntimeTaskAuditDTO audit;
        try {
            audit = new BusinessAgentApi(runtimeAuditHttp()).auditRuntimeTask(
                    appKey,
                    appSecret,
                    upstreamUserId,
                    taskId);
        } catch (NavigatorApiException e) {
            throw runtimeStateAuditFailure(e, "RUNTIME_TASK_AUDIT_QUERY_FAILED");
        }
        if (audit == null) {
            throw new RuntimeRequestFailure("sanitizedErrorCode=RUNTIME_TASK_AUDIT_EMPTY_RESPONSE");
        }
        if (args.flag("json")) {
            printJson(audit);
        } else {
            printRuntimeTaskAudit(audit);
        }
        return 0;
    }

    private int runtimeTerminationReadiness(CliArguments args) throws Exception {
        String taskId = requiredOption(args, "task-id", "task id");
        String expectedWorkerId = requiredOption(
                args, "expected-physical-worker-id", "expected physical worker id");
        Map<String, Object> result;
        try {
            result = new BusinessAgentApi(runtimeAuditHttp()).runtimeTerminationReadiness(
                    clientAppKey(args),
                    config.required("NAVI_CLIENT_APP_SECRET", "client app secret"),
                    upstreamUserId(args), taskId, expectedWorkerId);
        } catch (NavigatorApiException e) {
            throw runtimeStateAuditFailure(e, "RUNTIME_TERMINATION_READINESS_FAILED");
        }
        printJson(result);
        return 0;
    }

    private int runtimeTaskCompletionReadiness(CliArguments args) throws Exception {
        String taskId = requiredOption(args, "task-id", "task id");
        String expectedWorkerId = requiredOption(
                args, "expected-physical-worker-id", "expected physical worker id");
        RuntimeTaskCompletionReadinessDTO result;
        try {
            result = new BusinessAgentApi(runtimeAuditHttp()).getRuntimeTaskCompletionReadiness(
                    clientAppKey(args),
                    config.required("NAVI_CLIENT_APP_SECRET", "client app secret"),
                    upstreamUserId(args), taskId, expectedWorkerId);
        } catch (NavigatorApiException e) {
            throw runtimeStateAuditFailure(
                    e, "RUNTIME_TASK_COMPLETION_READINESS_FAILED");
        }
        printJson(result);
        return 0;
    }

    private int runtimeTaskTerminate(CliArguments args) throws Exception {
        String taskId = requiredOption(args, "task-id", "task id");
        String expectedWorkerId = requiredOption(
                args, "expected-physical-worker-id", "expected physical worker id");
        boolean dryRun = args.flag("dry-run");
        String confirmTaskId = args.option("confirm-task-id");
        if (!dryRun && !taskId.equals(confirmTaskId)) {
            throw new UpstreamCliException("task-terminate requires --confirm-task-id equal to --task-id");
        }
        String clientRequestId = beginRuntimeClientRequest(
                "task-terminate", null, upstreamUserId(args),
                args.option("replay-client-request-id"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("expectedPhysicalWorkerId", expectedWorkerId);
        body.put("reason", requiredOption(args, "reason", "termination reason"));
        body.put("dryRun", dryRun);
        if (!dryRun) body.put("confirmTaskId", confirmTaskId);
        try {
            Map<String, Object> result = new BusinessAgentApi(runtimeAuditHttp()).runtimeTaskTerminate(
                    clientAppKey(args),
                    config.required("NAVI_CLIENT_APP_SECRET", "client app secret"),
                    upstreamUserId(args), clientRequestId, body);
            printJson(result);
            return 0;
        } catch (NavigatorApiException e) {
            throw runtimeRequestFailure(e, "RUNTIME_TASK_TERMINATE_FAILED", clientRequestId);
        }
    }

    private int runtimeTaskReconcile(CliArguments args) throws Exception {
        String taskId = requiredOption(args, "task-id", "task id");
        String originalClientRequestId = requiredOption(
                args, "replay-client-request-id", "original termination request id");
        String clientRequestId = beginRuntimeClientRequest(
                "task-reconcile", null, upstreamUserId(args),
                originalClientRequestId);
        RuntimeTaskReconcileForm form = new RuntimeTaskReconcileForm();
        form.setTaskId(taskId);
        try {
            RuntimeTaskReconciliationDTO result =
                    new BusinessAgentApi(runtimeAuditHttp()).reconcileRuntimeTaskTermination(
                    clientAppKey(args),
                    config.required("NAVI_CLIENT_APP_SECRET", "client app secret"),
                    upstreamUserId(args), clientRequestId, form);
            printJson(result);
            return 0;
        } catch (NavigatorApiException e) {
            throw runtimeRequestFailure(e, "RUNTIME_TASK_RECONCILE_FAILED", clientRequestId);
        }
    }

    private int runtimeTaskTerminalCleanupRepair(CliArguments args) throws Exception {
        String taskId = requiredOption(args, "task-id", "task id");
        String expectedWorkerId = requiredOption(
                args, "expected-physical-worker-id", "expected physical worker id");
        boolean dryRun = args.flag("dry-run");
        String confirmTaskId = args.option("confirm-task-id");
        String replayClientRequestId = args.option("replay-client-request-id");
        if (dryRun && hasText(confirmTaskId)) {
            throw new UpstreamCliException(
                    "task-terminal-cleanup-repair accepts either --dry-run or --confirm-task-id");
        }
        if (!dryRun) {
            if (!taskId.equals(confirmTaskId)) {
                throw new UpstreamCliException(
                        "task-terminal-cleanup-repair requires --confirm-task-id equal to --task-id");
            }
            if (!hasText(replayClientRequestId)) {
                throw new UpstreamCliException(
                        "task-terminal-cleanup-repair confirmation requires the dry-run --replay-client-request-id");
            }
        }
        String clientRequestId = beginRuntimeClientRequest(
                "task-terminal-cleanup-repair", null, upstreamUserId(args),
                replayClientRequestId);
        RuntimeTaskTerminalCleanupRepairForm form = new RuntimeTaskTerminalCleanupRepairForm();
        form.setTaskId(taskId);
        form.setExpectedPhysicalWorkerId(expectedWorkerId);
        form.setDryRun(dryRun);
        form.setConfirmTaskId(dryRun ? null : confirmTaskId);
        try {
            RuntimeTaskTerminalCleanupRepairDTO result =
                    new BusinessAgentApi(runtimeAuditHttp()).repairRuntimeTaskTerminalCleanup(
                            clientAppKey(args),
                            config.required("NAVI_CLIENT_APP_SECRET", "client app secret"),
                            upstreamUserId(args), clientRequestId, form);
            printJson(result);
            return 0;
        } catch (NavigatorApiException e) {
            throw runtimeRequestFailure(
                    e, "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_FAILED", clientRequestId);
        }
    }

    private int upstreamClientAppIssueControlKey(CliArguments args, boolean legacyAlias) {
        if (!args.flag("write-profile")) {
            throw new UpstreamCliException("platform app issue-control-key requires --write-profile to store NAVI_CONTROL_API_KEY without printing it");
        }
        Path targetProfile = platformControlProfilePath(args, legacyAlias);

        IssueControlCredentialForm form = new IssueControlCredentialForm();
        form.setDescription(args.option("description"));
        form.setEffectiveUserId(args.option("effective-user-id"));
        form.setScopes(parseCsv(args.option("scopes")));
        form.setExpiresAt(parseLocalDateTimeOption(args.option("expires-at"), "expires at"));

        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        IssuedCredentialDTO credential = upstreamAdminApi()
                .issueUpstreamClientAppControlCredential(clientAppId, form);
        if (credential == null || !hasText(credential.getControlApiKey())) {
            throw new UpstreamCliException("client-app issue-control-key response did not include NAVI_CONTROL_API_KEY");
        }

        config.writeProfileValues(targetProfile, Map.of(
                "NAVI_BASE_URL", config.required("NAVI_BASE_URL", "Navigator base URL"),
                "NAVI_TENANT_ID", emptyIfNull(credential.getTenantId()),
                "NAVI_CLIENT_APP_ID", emptyIfNull(credential.getClientAppId()),
                "NAVI_CONTROL_API_KEY", credential.getControlApiKey()), PLATFORM_CONTROL_PROFILE_FORBIDDEN);

        out.println(legacyAlias ? "client-app issue-control-key ok" : "platform app issue-control-key ok");
        out.println("profileUpdated=" + targetProfile);
        out.println("stored=NAVI_CONTROL_API_KEY");
        out.println("credentialId=" + valueOrEmpty(credential.getCredentialId()));
        out.println("clientAppId=" + valueOrEmpty(credential.getClientAppId()));
        out.println("tenantId=" + valueOrEmpty(credential.getTenantId()));
        out.println("controlApiKey=" + SecretMasker.mask(credential.getControlApiKey()));
        out.println("scopes=" + joinList(credential.getScopes()));
        out.println("expiresAt=" + valueOrEmpty(credential.getExpiresAt()));
        return 0;
    }

    private int upstreamClientAppIssueRuntimeKey(CliArguments args, boolean legacyAlias) {
        if (!args.flag("write-profile")) {
            throw new UpstreamCliException("platform app issue-runtime-key requires --write-profile to store NAVI_CLIENT_APP_KEY and NAVI_CLIENT_APP_SECRET without printing them");
        }
        Path targetProfile = tenantRuntimeProfilePath(args, legacyAlias);

        IssueRuntimeCredentialForm form = new IssueRuntimeCredentialForm();
        form.setDescription(args.option("description"));
        form.setExpiresAt(parseLocalDateTimeOption(args.option("expires-at"), "expires at"));

        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        IssuedCredentialDTO credential = upstreamAdminApi()
                .issueUpstreamClientAppRuntimeCredential(clientAppId, form);
        if (credential == null || !hasText(credential.getAppKey()) || !hasText(credential.getSecret())) {
            throw new UpstreamCliException("client-app issue-runtime-key response did not include NAVI_CLIENT_APP_KEY and NAVI_CLIENT_APP_SECRET");
        }

        config.writeProfileValues(targetProfile, Map.of(
                "NAVI_BASE_URL", config.required("NAVI_BASE_URL", "Navigator base URL"),
                "NAVI_TENANT_ID", emptyIfNull(credential.getTenantId()),
                "NAVI_CLIENT_APP_ID", emptyIfNull(credential.getClientAppId()),
                "NAVI_CLIENT_APP_KEY", credential.getAppKey(),
                "NAVI_CLIENT_APP_SECRET", credential.getSecret(),
                "NAVI_CLIENT_APP_ACCESS_TOKEN", ""), TENANT_RUNTIME_PROFILE_FORBIDDEN);

        out.println(legacyAlias ? "client-app issue-runtime-key ok" : "platform app issue-runtime-key ok");
        out.println("profileUpdated=" + targetProfile);
        out.println("stored=NAVI_CLIENT_APP_KEY,NAVI_CLIENT_APP_SECRET,NAVI_CLIENT_APP_ACCESS_TOKEN");
        out.println("credentialId=" + valueOrEmpty(credential.getCredentialId()));
        out.println("clientAppId=" + valueOrEmpty(credential.getClientAppId()));
        out.println("tenantId=" + valueOrEmpty(credential.getTenantId()));
        out.println("clientAppKey=" + SecretMasker.mask(credential.getAppKey()));
        out.println("clientAppKeySha256=" + SecretMasker.sha256Hex(credential.getAppKey()));
        out.println("clientAppSecretSha256=" + SecretMasker.sha256Hex(credential.getSecret()));
        out.println("rotateRuntimeCredential=" + args.flag("rotate-runtime-credential"));
        out.println("expiresAt=" + valueOrEmpty(credential.getExpiresAt()));
        return 0;
    }

    private int workerList(CliArguments args) {
        List<Worker> workers = upstreamAdminWorkerApi()
                .listWithUpstreamAdmin(optionalOptionOrConfig(args, "target-tenant-id", "NAVI_TARGET_TENANT_ID"));
        out.println("workerCount=" + (workers != null ? workers.size() : 0));
        if (workers != null) {
            workers.forEach(this::printWorker);
        }
        return 0;
    }

    private int workerCreate(CliArguments args) throws Exception {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        Worker worker = upstreamAdminWorkerApi().createWithUpstreamAdmin(
                readJsonMap(requiredOption(args, "file", "worker json file")),
                optionalOptionOrConfig(args, "target-tenant-id", "NAVI_TARGET_TENANT_ID"));
        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_WORKER_ID", valueOrEmpty(worker != null ? worker.getWorkerId() : null));
        }
        out.println("worker create ok");
        printWorker(worker);
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_WORKER_ID");
        }
        return 0;
    }

    private int workerGet(CliArguments args) {
        printWorker(upstreamAdminWorkerApi().getWithUpstreamAdmin(requiredOptionOrConfig(args, "worker-id", "NAVI_WORKER_ID", "worker id")));
        return 0;
    }

    private int workerUpdate(CliArguments args) throws Exception {
        Worker worker = upstreamAdminWorkerApi().updateWithUpstreamAdmin(
                requiredOptionOrConfig(args, "worker-id", "NAVI_WORKER_ID", "worker id"),
                readJsonMap(requiredOption(args, "file", "worker json file")));
        out.println("worker update ok");
        printWorker(worker);
        return 0;
    }

    private int workerDelete(CliArguments args) {
        String workerId = requiredOptionOrConfig(args, "worker-id", "NAVI_WORKER_ID", "worker id");
        upstreamAdminWorkerApi().deleteWithUpstreamAdmin(workerId);
        out.println("worker delete ok");
        out.println("workerId=" + valueOrEmpty(workerId));
        return 0;
    }

    private int workerHealth(CliArguments args) {
        Worker worker = upstreamAdminWorkerApi().healthCheckWithUpstreamAdmin(
                requiredOptionOrConfig(args, "worker-id", "NAVI_WORKER_ID", "worker id"));
        out.println("worker health ok");
        printWorker(worker);
        return 0;
    }

    private int workerProcesses(CliArguments args) throws Exception {
        printJson(upstreamAdminWorkerApi().listProcessesWithUpstreamAdmin(
                requiredOptionOrConfig(args, "worker-id", "NAVI_WORKER_ID", "worker id")));
        return 0;
    }

    private int workerKill(CliArguments args) throws Exception {
        printJson(upstreamAdminWorkerApi().killProcessWithUpstreamAdmin(
                requiredOptionOrConfig(args, "worker-id", "NAVI_WORKER_ID", "worker id"),
                parseInteger(requiredOption(args, "pid", "process pid")),
                args.flag("force")));
        return 0;
    }

    private int workerHostApply(CliArguments args) throws Exception {
        return workerHostApplyOrUpdate(args, true);
    }

    private int workerHostUpdate(CliArguments args) throws Exception {
        return workerHostApplyOrUpdate(args, false);
    }

    private int workerHostApplyOrUpdate(CliArguments args, boolean createIfMissing) throws Exception {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        WorkerHostPlan plan = normalizeWorkerHostManifest(readJsonFile(
                requiredOption(args, "file", "worker host json file"), WorkerHostManifest.class));
        WorkerApi workerApi = upstreamAdminWorkerApi();
        String workerId = firstNonBlank(
                args.option("worker-id"),
                plan.claudeCode.workerId,
                config.get("NAVI_WORKER_ID"));
        Worker claudeWorker;
        boolean created = !hasText(workerId);
        if (created) {
            if (!createIfMissing) {
                throw new UpstreamCliException("claudeCode worker id is required for worker-host update (--worker-id or NAVI_WORKER_ID)");
            }
            claudeWorker = workerApi.createWithUpstreamAdmin(
                    buildClaudeWorkerBody(plan),
                    optionalOptionOrConfig(args, "target-tenant-id", "NAVI_TARGET_TENANT_ID"));
            workerId = claudeWorker != null ? claudeWorker.getWorkerId() : null;
        } else {
            claudeWorker = workerApi.updateWithUpstreamAdmin(workerId, buildClaudeWorkerBody(plan));
            if (claudeWorker != null && hasText(claudeWorker.getWorkerId())) {
                workerId = claudeWorker.getWorkerId();
            }
        }

        Map<String, Object> bizWorker = null;
        if (plan.biz != null) {
            bizWorker = upstreamAdminApi().registerUpstreamWorkerIdentity(buildBizWorkerIdentityBody(plan));
        }

        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_WORKER_HOST_ID", valueOrEmpty(plan.workerHostId));
            config.writeProfileValue("NAVI_WORKER_ID", valueOrEmpty(workerId));
            if (bizWorker != null) {
                config.writeProfileValue("NAVI_BIZ_WORKER_ID", valueOrEmpty(bizWorker.get("workerId")));
            }
        }

        out.println("worker-host " + (createIfMissing ? "apply" : "update") + " ok");
        out.println("workerHost workerHostId=" + valueOrEmpty(plan.workerHostId)
                + " hostUrl=" + redact(plan.hostUrl)
                + " install=" + valueOrEmpty(plan.install)
                + " claudeCodeAction=" + (created ? "create" : "update"));
        printWorkerHostRole("claudeCode", workerId, plan.claudeCode.baseUrl, "CLAUDE_WORKER");
        if (plan.codex != null) {
            printWorkerHostRole("codex", workerId, plan.codex.baseUrl, "CLAUDE_WORKER_CODEX_CONFIG");
        }
        if (plan.biz != null) {
            Object bizWorkerId = bizWorker != null ? bizWorker.get("workerId") : plan.biz.workerId;
            printWorkerHostRole("biz", valueOrEmpty(bizWorkerId), plan.biz.baseUrl, "BIZ_WORKER_IDENTITY");
        }
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_WORKER_HOST_ID,NAVI_WORKER_ID" + (plan.biz != null ? ",NAVI_BIZ_WORKER_ID" : ""));
        }
        return 0;
    }

    private int workerHostVerify(CliArguments args) throws Exception {
        WorkerHostPlan plan = normalizeWorkerHostManifest(readJsonFile(
                requiredOption(args, "file", "worker host json file"), WorkerHostManifest.class));
        String claudeWorkerId = firstNonBlank(plan.claudeCode.workerId, config.get("NAVI_WORKER_ID"));
        out.println("worker-host verify ok");
        out.println("workerHost workerHostId=" + valueOrEmpty(plan.workerHostId)
                + " hostUrl=" + redact(plan.hostUrl)
                + " install=" + valueOrEmpty(plan.install));
        printWorkerHostRole("claudeCode", claudeWorkerId, plan.claudeCode.baseUrl, "CLAUDE_WORKER");
        if (plan.codex != null) {
            printWorkerHostRole("codex", claudeWorkerId, plan.codex.baseUrl, "CLAUDE_WORKER_CODEX_CONFIG");
        }
        if (plan.biz != null) {
            printWorkerHostRole("biz", plan.biz.workerId, plan.biz.baseUrl, "BIZ_WORKER_IDENTITY");
        }
        return 0;
    }

    private int workerHostInstall(CliArguments args) throws Exception {
        WorkerHostPlan plan = normalizeWorkerHostManifest(readJsonFile(
                requiredOption(args, "file", "worker host json file"), WorkerHostManifest.class));
        String installShell = normalizeInstallShell(firstNonBlank(args.option("install-shell"), "auto"));
        WslInstallOptions wslOptions = resolveWslInstallOptions(args, plan, installShell);
        boolean startAfterInstall = !args.flag("no-start");
        Duration timeout = Duration.ofSeconds(parseInteger(args.option("timeout-seconds"), 1800));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new UpstreamCliException("timeout-seconds must be greater than 0");
        }
        List<InstallerCommand> installerCommands = buildInstallerCommands(plan, installShell, wslOptions);
        List<StartCommand> startCommands = startAfterInstall
                ? buildWorkerHostStartCommands(plan, installShell, wslOptions)
                : List.of();

        out.println("worker-host install " + (args.flag("dry-run") ? "plan ok" : "start"));
        out.println("workerHost workerHostId=" + valueOrEmpty(plan.workerHostId)
                + " hostUrl=" + redact(plan.hostUrl)
                + " install=" + valueOrEmpty(plan.install));
        out.println("installShell=" + installShell);
        if ("wsl".equals(installShell)) {
            out.println("wslDistro=" + firstNonBlank(wslOptions.distro(), "default"));
            out.println("wslUser=" + firstNonBlank(wslOptions.user(), "default"));
        }
        out.println("timeoutSeconds=" + timeout.toSeconds());
        out.println("startAfterInstall=" + startAfterInstall);
        printWorkerHostRole("claudeCode", plan.claudeCode.workerId, plan.claudeCode.baseUrl, "CLAUDE_WORKER");
        if (plan.codex != null) {
            printWorkerHostRole("codex", plan.claudeCode.workerId, plan.codex.baseUrl, "CLAUDE_WORKER_CODEX_CONFIG");
        }
        if (plan.biz != null) {
            printWorkerHostRole("biz", plan.biz.workerId, plan.biz.baseUrl, "BIZ_WORKER_IDENTITY");
        }
        for (InstallerCommand installerCommand : installerCommands) {
            out.println("installer role=" + installerCommand.role()
                    + " url=" + installerCommand.releaseBaseUrl()
                    + " command=" + redact(String.join(" ", installerCommand.command()))
                    + (hasText(installerCommand.scriptPreview())
                    ? " script=" + redact(installerCommand.scriptPreview())
                    : ""));
        }
        for (StartCommand startCommand : startCommands) {
            out.println("starter role=" + startCommand.role()
                    + " command=" + redact(String.join(" ", startCommand.command()))
                    + (hasText(startCommand.scriptPreview())
                    ? " script=" + redact(startCommand.scriptPreview())
                    : ""));
        }
        if (args.flag("dry-run")) {
            out.println("automaticInstall=false");
            out.println("message=dry-run; installer commands were not executed");
            return 0;
        }

        out.println("automaticInstall=true");
        for (InstallerCommand installerCommand : installerCommands) {
            out.println("install role=" + installerCommand.role()
                    + " status=STARTED"
                    + " url=" + installerCommand.releaseBaseUrl());
            CommandResult result = commandRunner.run(installerCommand.command(), timeout);
            printInstallerOutput(installerCommand.role(), result.output());
            if (result.exitCode() != 0) {
                throw new UpstreamCliException("worker-host install failed role="
                        + installerCommand.role() + " exitCode=" + result.exitCode());
            }
            out.println("install role=" + installerCommand.role()
                    + " status=OK"
                    + " exitCode=" + result.exitCode());
        }
        if (startAfterInstall) {
            for (StartCommand startCommand : startCommands) {
                out.println("start role=" + startCommand.role() + " status=STARTED");
                CommandResult result = commandRunner.run(startCommand.command(), timeout);
                printInstallerOutput(startCommand.role(), result.output());
                if (result.exitCode() != 0) {
                    throw new UpstreamCliException("worker-host start failed role="
                            + startCommand.role() + " exitCode=" + result.exitCode());
                }
                out.println("start role=" + startCommand.role()
                        + " status=OK"
                        + " exitCode=" + result.exitCode());
            }
        }
        out.println("worker-host install ok");
        return 0;
    }

    private WslInstallOptions resolveWslInstallOptions(CliArguments args,
                                                       WorkerHostPlan plan,
                                                       String installShell) {
        String wslUser = firstNonBlank(args.option("wsl-user"), plan.wslUser, env.get("NAVI_WSL_USER"));
        String wslDistro = firstNonBlank(args.option("wsl-distro"), plan.wslDistro, env.get("NAVI_WSL_DISTRO"));
        if (!"wsl".equals(installShell)
                && (hasText(args.option("wsl-user")) || hasText(args.option("wsl-distro")))) {
            throw new UpstreamCliException("--wsl-user/--wsl-distro require --install-shell wsl");
        }
        return new WslInstallOptions(wslDistro, wslUser);
    }

    private List<InstallerCommand> buildInstallerCommands(WorkerHostPlan plan,
                                                          String installShell,
                                                          WslInstallOptions wslOptions) {
        List<InstallerCommand> commands = new ArrayList<>();
        commands.add(buildInstallerCommand("claudeCode", CLAUDE_WORKER_INSTALL_BASE_URL, installShell,
                "CLAUDE_WORKER_HOME", ".claude-worker", "AGENT_WORKER_PORT",
                portFromBaseUrl(plan.claudeCode.baseUrl), wslOptions));
        if (plan.codex != null) {
            commands.add(buildInstallerCommand("codex", CODEX_WORKER_INSTALL_BASE_URL, installShell,
                    "CODEX_WORKER_HOME", ".codex-worker", "CODEX_WORKER_PORT",
                    portFromBaseUrl(plan.codex.baseUrl), wslOptions));
        }
        if (plan.biz != null) {
            commands.add(buildInstallerCommand("biz", BIZ_WORKER_INSTALL_BASE_URL, installShell,
                    "LANGGRAPH_BIZ_WORKER_HOME", ".langgraph-biz-worker", "BIZ_WORKER_PORT",
                    portFromBaseUrl(plan.biz.baseUrl), wslOptions));
        }
        return commands;
    }

    private InstallerCommand buildInstallerCommand(String role,
                                                   String releaseBaseUrl,
                                                   String installShell,
                                                   String homeEnvName,
                                                   String defaultHomeDir,
                                                   String portEnvName,
                                                   Integer port,
                                                   WslInstallOptions wslOptions) {
        String bashScript = buildBashInstallScript(releaseBaseUrl + "/install.sh",
                homeEnvName, defaultHomeDir, portEnvName, port);
        return switch (installShell) {
            case "powershell" -> new InstallerCommand(role, releaseBaseUrl,
                    buildPowerShellInstallCommand(releaseBaseUrl + "/install.ps1",
                            homeEnvName, defaultHomeDir, portEnvName, port), null);
            case "bash" -> new InstallerCommand(role, releaseBaseUrl,
                    List.of("bash", "-lc", bashScript), null);
            case "wsl" -> new InstallerCommand(role, releaseBaseUrl,
                    buildWslCommand(bashScript, wslOptions), bashScript);
            default -> throw new UpstreamCliException("unsupported install shell: " + installShell);
        };
    }

    private List<String> buildWslCommand(String bashScript, WslInstallOptions wslOptions) {
        String encoded = Base64.getEncoder().encodeToString(bashScript.getBytes(StandardCharsets.UTF_8));
        List<String> command = new ArrayList<>();
        command.add("wsl.exe");
        if (hasText(wslOptions.distro())) {
            command.add("--distribution");
            command.add(wslOptions.distro());
        }
        if (hasText(wslOptions.user())) {
            command.add("--user");
            command.add(wslOptions.user());
        }
        command.add("--exec");
        command.add("bash");
        command.add("-lc");
        command.add("printf %s " + shellQuote(encoded) + " | base64 -d | bash");
        return command;
    }

    private List<StartCommand> buildWorkerHostStartCommands(WorkerHostPlan plan,
                                                            String installShell,
                                                            WslInstallOptions wslOptions) {
        List<StartCommand> commands = new ArrayList<>();
        commands.add(buildWorkerStartCommand("claudeCode", installShell,
                "CLAUDE_WORKER_HOME", ".claude-worker", "claude-worker",
                portFromBaseUrl(plan.claudeCode.baseUrl), wslOptions));
        if (plan.codex != null) {
            commands.add(buildCodexWorkerStartCommand(installShell,
                    portFromBaseUrl(plan.codex.baseUrl), wslOptions));
        }
        if (plan.biz != null) {
            commands.add(buildBizWorkerStartCommand(installShell,
                    portFromBaseUrl(plan.biz.baseUrl), wslOptions));
        }
        return commands;
    }

    private StartCommand buildWorkerStartCommand(String role,
                                                 String installShell,
                                                 String homeEnvName,
                                                 String defaultHomeDir,
                                                 String cliName,
                                                 Integer port,
                                                 WslInstallOptions wslOptions) {
        String bashScript = buildBashWorkerStartScript(homeEnvName, defaultHomeDir, cliName);
        return switch (installShell) {
            case "powershell" -> new StartCommand(role,
                    buildPowerShellWorkerStartCommand(homeEnvName, defaultHomeDir, cliName), null);
            case "bash" -> new StartCommand(role,
                    List.of("bash", "-lc", bashScript), null);
            case "wsl" -> new StartCommand(role,
                    buildWslCommand(bashScript, wslOptions), bashScript);
            default -> throw new UpstreamCliException("unsupported install shell: " + installShell);
        };
    }

    private StartCommand buildCodexWorkerStartCommand(String installShell,
                                                      Integer port,
                                                      WslInstallOptions wslOptions) {
        int resolvedPort = port != null ? port : 3051;
        String bashScript = buildBashCodexWorkerStartScript(resolvedPort);
        return switch (installShell) {
            case "powershell" -> new StartCommand("codex",
                    buildPowerShellCodexWorkerStartCommand(resolvedPort), null);
            case "bash" -> new StartCommand("codex",
                    List.of("bash", "-lc", bashScript), null);
            case "wsl" -> new StartCommand("codex",
                    buildWslCommand(bashScript, wslOptions), bashScript);
            default -> throw new UpstreamCliException("unsupported install shell: " + installShell);
        };
    }

    private StartCommand buildBizWorkerStartCommand(String installShell,
                                                    Integer port,
                                                    WslInstallOptions wslOptions) {
        int resolvedPort = port != null ? port : 3065;
        String bashScript = buildBashBizWorkerStartScript(resolvedPort);
        return switch (installShell) {
            case "powershell" -> new StartCommand("biz",
                    buildPowerShellBizWorkerStartCommand(resolvedPort), null);
            case "bash" -> new StartCommand("biz",
                    List.of("bash", "-lc", bashScript), null);
            case "wsl" -> new StartCommand("biz",
                    buildWslCommand(bashScript, wslOptions), bashScript);
            default -> throw new UpstreamCliException("unsupported install shell: " + installShell);
        };
    }

    private String buildBashWorkerStartScript(String homeEnvName,
                                             String defaultHomeDir,
                                             String cliName) {
        return "set -e; \"${" + homeEnvName + ":-$HOME/" + defaultHomeDir + "}/bin/" + cliName + "\" start";
    }

    private String buildBashCodexWorkerStartScript(int port) {
        return String.join("\n",
                "set -e",
                "dir=\"${CODEX_WORKER_HOME:-$HOME/.codex-worker}\"",
                "cd \"$dir\"",
                "mkdir -p logs",
                "pids=\"$(lsof -ti:" + port + " 2>/dev/null || true)\"",
                "if [ -n \"$pids\" ]; then kill -9 $pids 2>/dev/null || true; sleep 1; fi",
                "if [ -f dist/index.js ]; then run_cmd='node dist/index.js'; else run_cmd='npx tsx src/index.ts'; fi",
                "pid_file=\"logs/worker.pid\"",
                "rm -f \"$pid_file\"",
                "pid=\"\"",
                "if command -v setsid >/dev/null 2>&1; then",
                "  setsid -f sh -c \"echo \\$\\$ > logs/worker.pid; exec $run_cmd\" "
                        + "> logs/worker.log 2> logs/worker-error.log < /dev/null",
                "else",
                "  nohup sh -c \"echo \\$\\$ > logs/worker.pid; exec $run_cmd\" "
                        + "> logs/worker.log 2> logs/worker-error.log < /dev/null &",
                "  pid=$!",
                "  disown \"$pid\" 2>/dev/null || true",
                "fi",
                "sleep 1",
                "file_pid=\"$(cat \"$pid_file\" 2>/dev/null || true)\"",
                "if [ -n \"$file_pid\" ]; then pid=\"$file_pid\"; fi",
                "if [ -z \"$pid\" ]; then echo \"Codex Worker pid file was not created\"; "
                        + "tail -20 logs/worker-error.log 2>/dev/null || true; exit 1; fi",
                "for i in $(seq 1 30); do",
                "  sleep 1",
                "  if curl -fsS --max-time 2 http://localhost:" + port + "/health >/dev/null 2>&1; then break; fi",
                "  if ! kill -0 \"$pid\" 2>/dev/null; then echo \"Codex Worker exited\"; "
                        + "tail -20 logs/worker-error.log 2>/dev/null || true; exit 1; fi",
                "  if [ \"$i\" = \"30\" ]; then echo \"Codex Worker failed to start on port " + port + "\"; "
                        + "tail -20 logs/worker-error.log 2>/dev/null || true; exit 1; fi",
                "done",
                "sleep 3",
                "if ! kill -0 \"$pid\" 2>/dev/null; then echo \"Codex Worker exited after readiness\"; "
                        + "tail -20 logs/worker-error.log 2>/dev/null || true; exit 1; fi",
                "if ! curl -fsS --max-time 2 http://localhost:" + port + "/health >/dev/null 2>&1; then "
                        + "echo \"Codex Worker health failed after readiness\"; "
                        + "tail -20 logs/worker-error.log 2>/dev/null || true; exit 1; fi",
                "echo \"Codex Worker READY http://localhost:" + port + "\"");
    }

    private List<String> buildPowerShellWorkerStartCommand(String homeEnvName,
                                                           String defaultHomeDir,
                                                           String cliName) {
        String executable = isWindows() ? "powershell" : "pwsh";
        String script = "$ErrorActionPreference='Stop'; "
                + "$dir=if ($env:" + homeEnvName + ") { $env:" + homeEnvName + " } else { Join-Path $HOME "
                + powerShellSingleQuote(defaultHomeDir) + " }; "
                + "$cli=Join-Path $dir " + powerShellSingleQuote("bin/" + cliName + ".ps1") + "; "
                + "if (-not (Test-Path $cli)) { throw ('worker cli not found: ' + $cli) }; "
                + "& powershell -ExecutionPolicy Bypass -File $cli start; "
                + "if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }";
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-NoProfile");
        if (isWindows()) {
            command.add("-ExecutionPolicy");
            command.add("Bypass");
        }
        command.add("-Command");
        command.add(script);
        return command;
    }

    private List<String> buildPowerShellCodexWorkerStartCommand(int port) {
        String executable = isWindows() ? "powershell" : "pwsh";
        String script = "$ErrorActionPreference='Stop'; "
                + "$dir=if ($env:CODEX_WORKER_HOME) { $env:CODEX_WORKER_HOME } else { Join-Path $HOME '.codex-worker' }; "
                + "Set-Location $dir; "
                + "$existing=(netstat -ano | Select-String ':" + port + "\\s+.*LISTENING' | Select-Object -First 1); "
                + "if ($existing) { $pidText=($existing.ToString() -split '\\s+')[-1]; "
                + "taskkill /F /PID $pidText 2>$null | Out-Null; Start-Sleep -Milliseconds 500 }; "
                + "$logDir=Join-Path $dir 'logs'; "
                + "if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }; "
                + "if (Test-Path (Join-Path $dir 'dist\\index.js')) { "
                + "$file='node'; $arguments=@('dist/index.js') "
                + "} else { "
                + "$file=if ($env:OS -eq 'Windows_NT') { 'npx.cmd' } else { 'npx' }; "
                + "$arguments=@('tsx','src/index.ts') "
                + "}; "
                + "$process=Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $dir "
                + "-RedirectStandardOutput (Join-Path $logDir 'worker.log') "
                + "-RedirectStandardError (Join-Path $logDir 'worker-error.log') -WindowStyle Hidden -PassThru; "
                + "$ok=$false; "
                + "for ($i=0; $i -lt 30; $i++) { "
                + "Start-Sleep -Seconds 1; "
                + "if ($process.HasExited) { "
                + "Get-Content (Join-Path $logDir 'worker-error.log') -Tail 20 -ErrorAction SilentlyContinue; "
                + "throw 'Codex Worker exited' "
                + "}; "
                + "try { Invoke-RestMethod -Uri 'http://localhost:" + port + "/health' -TimeoutSec 2 -ErrorAction Stop | Out-Null; "
                + "$ok=$true; break } catch { } "
                + "}; "
                + "if (-not $ok) { "
                + "Get-Content (Join-Path $logDir 'worker-error.log') -Tail 20 -ErrorAction SilentlyContinue; "
                + "throw 'Codex Worker failed to start' "
                + "}; "
                + "Start-Sleep -Seconds 3; "
                + "if ($process.HasExited) { "
                + "Get-Content (Join-Path $logDir 'worker-error.log') -Tail 20 -ErrorAction SilentlyContinue; "
                + "throw 'Codex Worker exited after readiness' "
                + "}; "
                + "Invoke-RestMethod -Uri 'http://localhost:" + port + "/health' -TimeoutSec 2 -ErrorAction Stop | Out-Null; "
                + "Write-Host 'Codex Worker READY http://localhost:" + port + "'";
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-NoProfile");
        if (isWindows()) {
            command.add("-ExecutionPolicy");
            command.add("Bypass");
        }
        command.add("-Command");
        command.add(script);
        return command;
    }

    private String buildBashBizWorkerStartScript(int port) {
        return String.join("\n",
                "set -e",
                "dir=\"${LANGGRAPH_BIZ_WORKER_HOME:-$HOME/.langgraph-biz-worker}\"",
                "cd \"$dir\"",
                "mkdir -p logs",
                "pids=\"$(lsof -ti:" + port + " 2>/dev/null || true)\"",
                "if [ -n \"$pids\" ]; then kill -9 $pids 2>/dev/null || true; sleep 1; fi",
                "py=\".venv/bin/python\"",
                "if [ ! -x \"$py\" ]; then py=\"$(command -v python3 || command -v python)\"; fi",
                "export PYTHONPATH=\"$dir/src\"",
                "export BIZ_WORKER_ENV_FILE=\"${BIZ_WORKER_ENV_FILE:-$dir/.env}\"",
                "nohup \"$py\" -m uvicorn langgraph_biz_worker.main:app --host 0.0.0.0 --port " + port
                        + " > logs/worker.log 2> logs/worker-error.log < /dev/null &",
                "pid=$!",
                "disown \"$pid\" 2>/dev/null || true",
                "for i in $(seq 1 30); do",
                "  sleep 1",
                "  if curl -fsS --max-time 2 http://localhost:" + port + "/health >/dev/null 2>&1; then "
                        + "echo \"LangGraph BizWorker READY http://localhost:" + port + "\"; exit 0; fi",
                "  if ! kill -0 \"$pid\" 2>/dev/null; then echo \"LangGraph BizWorker exited\"; "
                        + "tail -20 logs/worker-error.log 2>/dev/null || true; exit 1; fi",
                "done",
                "echo \"LangGraph BizWorker failed to start on port " + port + "\"",
                "tail -20 logs/worker-error.log 2>/dev/null || true",
                "exit 1");
    }

    private List<String> buildPowerShellBizWorkerStartCommand(int port) {
        String executable = isWindows() ? "powershell" : "pwsh";
        String script = "$ErrorActionPreference='Stop'; "
                + "$dir=if ($env:LANGGRAPH_BIZ_WORKER_HOME) { $env:LANGGRAPH_BIZ_WORKER_HOME } else { Join-Path $HOME '.langgraph-biz-worker' }; "
                + "Set-Location $dir; "
                + "$existing=(netstat -ano | Select-String ':" + port + "\\s+.*LISTENING' | Select-Object -First 1); "
                + "if ($existing) { $pidText=($existing.ToString() -split '\\s+')[-1]; taskkill /F /PID $pidText 2>$null | Out-Null; Start-Sleep -Milliseconds 500 }; "
                + "$venvPython=Join-Path $dir '.venv\\Scripts\\python.exe'; "
                + "if (-not (Test-Path $venvPython)) { throw ('venv python not found: ' + $venvPython) }; "
                + "$logDir=Join-Path $dir 'logs'; "
                + "if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }; "
                + "$env:PYTHONPATH=Join-Path $dir 'src'; "
                + "$env:BIZ_WORKER_ENV_FILE=Join-Path $dir '.env'; "
                + "Start-Process $venvPython -ArgumentList '-m','uvicorn','langgraph_biz_worker.main:app','--host','0.0.0.0','--port','" + port + "' "
                + "-WorkingDirectory $dir -RedirectStandardOutput (Join-Path $logDir 'worker.log') "
                + "-RedirectStandardError (Join-Path $logDir 'worker-error.log') -WindowStyle Hidden; "
                + "$ok=$false; "
                + "for ($i=0; $i -lt 30; $i++) { "
                + "Start-Sleep -Seconds 1; "
                + "try { Invoke-RestMethod -Uri 'http://localhost:" + port + "/health' -TimeoutSec 2 -ErrorAction Stop | Out-Null; $ok=$true; break } catch { } "
                + "}; "
                + "if (-not $ok) { "
                + "Get-Content (Join-Path $logDir 'worker-error.log') -Tail 20 -ErrorAction SilentlyContinue; "
                + "throw 'LangGraph BizWorker failed to start' "
                + "}; "
                + "Write-Host 'LangGraph BizWorker READY http://localhost:" + port + "'";
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-NoProfile");
        if (isWindows()) {
            command.add("-ExecutionPolicy");
            command.add("Bypass");
        }
        command.add("-Command");
        command.add(script);
        return command;
    }

    private List<String> buildPowerShellInstallCommand(String installUrl,
                                                       String homeEnvName,
                                                       String defaultHomeDir,
                                                       String portEnvName,
                                                       Integer port) {
        String executable = isWindows() ? "powershell" : "pwsh";
        String script = "$ErrorActionPreference='Stop'; irm " + powerShellSingleQuote(installUrl) + " | iex";
        if (port != null) {
            script += "; " + buildPowerShellPortConfigScript(homeEnvName, defaultHomeDir, portEnvName, port);
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-NoProfile");
        if (isWindows()) {
            command.add("-ExecutionPolicy");
            command.add("Bypass");
        }
        command.add("-Command");
        command.add(script);
        return command;
    }

    private String buildBashInstallScript(String installUrl,
                                          String homeEnvName,
                                          String defaultHomeDir,
                                          String portEnvName,
                                          Integer port) {
        String script = "set -e; curl -fsSL " + shellQuote(installUrl) + " | bash";
        if (port == null) {
            return script;
        }
        return script
                + "; env_file=\"${" + homeEnvName + ":-$HOME/" + defaultHomeDir + "}/.env\""
                + "; mkdir -p \"$(dirname \"$env_file\")\""
                + "; touch \"$env_file\""
                + "; if grep -q '^" + portEnvName + "=' \"$env_file\"; then "
                + "sed -i.bak 's|^" + portEnvName + "=.*|" + portEnvName + "=" + port + "|' \"$env_file\""
                + "; rm -f \"$env_file.bak\""
                + "; else printf '\\n" + portEnvName + "=" + port + "\\n' >> \"$env_file\""
                + "; fi";
    }

    private String buildPowerShellPortConfigScript(String homeEnvName,
                                                   String defaultHomeDir,
                                                   String portEnvName,
                                                   Integer port) {
        String line = portEnvName + "=" + port;
        return "$dir=if ($env:" + homeEnvName + ") { $env:" + homeEnvName + " } else { Join-Path $HOME "
                + powerShellSingleQuote(defaultHomeDir) + " }; "
                + "$envFile=Join-Path $dir '.env'; "
                + "if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }; "
                + "if (-not (Test-Path $envFile)) { New-Item -ItemType File -Force -Path $envFile | Out-Null }; "
                + "$content=Get-Content $envFile -Raw -ErrorAction SilentlyContinue; "
                + "if ($null -eq $content) { $content='' }; "
                + "if ($content -match '(?m)^" + portEnvName + "=') { "
                + "$content=$content -replace '(?m)^" + portEnvName + "=.*',"
                + powerShellSingleQuote(line)
                + " } else { $content=$content.TrimEnd()+\"`n" + line + "`n\" }; "
                + "$utf8=New-Object System.Text.UTF8Encoding($false); "
                + "[System.IO.File]::WriteAllText($envFile,$content,$utf8)";
    }

    private String normalizeInstallShell(String installShell) {
        String value = hasText(installShell) ? installShell.trim().toLowerCase() : "auto";
        if ("auto".equals(value)) {
            return isWindows() ? "powershell" : "bash";
        }
        if (!Set.of("powershell", "bash", "wsl").contains(value)) {
            throw new UpstreamCliException("install-shell must be one of auto,powershell,bash,wsl");
        }
        if ("wsl".equals(value) && !isWindows()) {
            throw new UpstreamCliException("install-shell=wsl is only supported when running the CLI on Windows");
        }
        return value;
    }

    private void printInstallerOutput(String role, String output) {
        if (!hasText(output)) {
            return;
        }
        out.println("installOutput role=" + role);
        out.print(redact(output));
        if (!output.endsWith("\n") && !output.endsWith("\r")) {
            out.println();
        }
    }

    private int directoryList(CliArguments args) {
        List<Directory> dirs = upstreamAdminDirectoryApi().listWithUpstreamAdmin(
                optionalOptionOrConfig(args, "target-tenant-id", "NAVI_TARGET_TENANT_ID"),
                optionalOptionOrConfig(args, "worker-id", "NAVI_WORKER_ID"));
        out.println("directoryCount=" + (dirs != null ? dirs.size() : 0));
        if (dirs != null) {
            dirs.forEach(this::printDirectory);
        }
        return 0;
    }

    private int directoryInit(CliArguments args) throws Exception {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        Directory dir = upstreamAdminDirectoryApi().initWithUpstreamAdmin(
                readJsonMap(requiredOption(args, "file", "directory init json file")));
        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_DIRECTORY_ID", valueOrEmpty(dir != null ? dir.getDirectoryId() : null));
        }
        out.println("directory init ok");
        printDirectory(dir);
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_DIRECTORY_ID");
        }
        return 0;
    }

    private int directoryGet(CliArguments args) {
        printDirectory(upstreamAdminDirectoryApi().getWithUpstreamAdmin(
                requiredOptionOrConfig(args, "directory-id", "NAVI_DIRECTORY_ID", "directory id")));
        return 0;
    }

    private int directoryDelete(CliArguments args) {
        String directoryId = requiredOptionOrConfig(args, "directory-id", "NAVI_DIRECTORY_ID", "directory id");
        upstreamAdminDirectoryApi().deleteWithUpstreamAdmin(directoryId);
        out.println("directory delete ok");
        out.println("directoryId=" + valueOrEmpty(directoryId));
        return 0;
    }

    private int directoryEnv(CliArguments args) throws Exception {
        printJson(upstreamAdminDirectoryApi().updateEnvVarsWithUpstreamAdmin(
                requiredOptionOrConfig(args, "directory-id", "NAVI_DIRECTORY_ID", "directory id"),
                readJsonStringMap(requiredOption(args, "file", "env json file"))));
        return 0;
    }

    private int directoryFiles(CliArguments args) throws Exception {
        printJson(upstreamAdminDirectoryApi().updateFilesWithUpstreamAdmin(
                requiredOptionOrConfig(args, "directory-id", "NAVI_DIRECTORY_ID", "directory id"),
                readJsonStringMap(requiredOption(args, "file", "files json file"))));
        return 0;
    }

    private int directoryClientList(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        List<Directory> dirs = clientAppDirectoryApi().listWithClientAppControl(
                clientAppId,
                optionalOptionOrConfig(args, "worker-id", "NAVI_WORKER_ID"),
                args.option("workspace-scope"),
                args.option("upstream-user-id"));
        out.println("directoryCount=" + (dirs != null ? dirs.size() : 0));
        if (dirs != null) {
            dirs.forEach(this::printDirectory);
        }
        return 0;
    }

    private int directoryClientInit(CliArguments args) throws Exception {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        Directory dir = clientAppDirectoryApi().initWithClientAppControl(
                clientAppId, readJsonMap(requiredOption(args, "file", "directory init json file")));
        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_DIRECTORY_ID", valueOrEmpty(dir != null ? dir.getDirectoryId() : null));
        }
        out.println("directory client-init ok");
        printDirectory(dir);
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_DIRECTORY_ID");
        }
        return 0;
    }

    private int directoryClientGet(CliArguments args) {
        printDirectory(clientAppDirectoryApi().getWithClientAppControl(
                requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id"),
                requiredOptionOrConfig(args, "directory-id", "NAVI_DIRECTORY_ID", "directory id")));
        return 0;
    }

    private int directoryClientDelete(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String directoryId = requiredOptionOrConfig(args, "directory-id", "NAVI_DIRECTORY_ID", "directory id");
        clientAppDirectoryApi().deleteWithClientAppControl(clientAppId, directoryId);
        out.println("directory client-delete ok");
        out.println("directoryId=" + valueOrEmpty(directoryId));
        return 0;
    }

    private int directoryClientEnv(CliArguments args) throws Exception {
        printJson(clientAppDirectoryApi().updateEnvVarsWithClientAppControl(
                requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id"),
                requiredOptionOrConfig(args, "directory-id", "NAVI_DIRECTORY_ID", "directory id"),
                readJsonStringMap(requiredOption(args, "file", "env json file"))));
        return 0;
    }

    private int directoryClientFiles(CliArguments args) throws Exception {
        printJson(clientAppDirectoryApi().updateFilesWithClientAppControl(
                requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id"),
                requiredOptionOrConfig(args, "directory-id", "NAVI_DIRECTORY_ID", "directory id"),
                readJsonStringMap(requiredOption(args, "file", "files json file"))));
        return 0;
    }

    private int workerPoolList(CliArguments args) throws Exception {
        printJson(upstreamAdminApi().listUpstreamWorkerPools(
                optionalOptionOrConfig(args, "target-tenant-id", "NAVI_TARGET_TENANT_ID")));
        return 0;
    }

    private int workerPoolCreate(CliArguments args) throws Exception {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        Map<String, Object> pool = upstreamAdminApi().createUpstreamWorkerPool(
                readJsonMap(requiredOption(args, "file", "worker pool json file")),
                optionalOptionOrConfig(args, "target-tenant-id", "NAVI_TARGET_TENANT_ID"));
        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_WORKER_POOL_ID", valueOrEmpty(pool != null ? pool.get("poolId") : null));
        }
        out.println("worker-pool create ok");
        printJson(pool);
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_WORKER_POOL_ID");
        }
        return 0;
    }

    private int workerPoolRegisterWorker(CliArguments args) throws Exception {
        Map<String, Object> workerIdentity = readJsonMap(requiredOption(args, "file", "biz worker identity json file"));
        String workerBackend = valueOrEmpty(workerIdentity.get("workerBackend"));
        if (isSubscriptionWorkerBackend(workerBackend)) {
            throw new UpstreamCliException("worker-pool register-worker does not support " + workerBackend
                    + "; configure Codex through worker-host verify then worker-host update --worker-id <physicalWorkerId>"
                    + " (use apply only for a new WorkerHost)");
        }
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        Map<String, Object> worker = upstreamAdminApi().registerUpstreamWorkerIdentity(workerIdentity);
        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_BIZ_WORKER_ID", valueOrEmpty(worker != null ? worker.get("workerId") : null));
        }
        out.println("worker-pool register-worker ok");
        printJson(worker);
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_BIZ_WORKER_ID");
        }
        return 0;
    }

    private int workerPoolAddMember(CliArguments args) {
        upstreamAdminApi().addUpstreamWorkerPoolMember(
                requiredOptionOrConfig(args, "pool-id", "NAVI_WORKER_POOL_ID", "worker pool id"),
                Map.of("workerId", workerPoolMemberWorkerId(args)),
                optionalOptionOrConfig(args, "target-tenant-id", "NAVI_TARGET_TENANT_ID"));
        out.println("worker-pool add-member ok");
        return 0;
    }

    private String workerPoolMemberWorkerId(CliArguments args) {
        String explicit = args.option("worker-id");
        if (hasText(explicit)) {
            return explicit;
        }
        String bizWorkerId = config.get("NAVI_BIZ_WORKER_ID");
        if (hasText(bizWorkerId)) {
            return bizWorkerId;
        }
        return requiredOptionOrConfig(args, "worker-id", "NAVI_WORKER_ID", "worker id");
    }

    private int workerPoolStatus(CliArguments args) throws Exception {
        printJson(upstreamAdminApi().updateUpstreamWorkerPoolStatus(
                requiredOptionOrConfig(args, "pool-id", "NAVI_WORKER_POOL_ID", "worker pool id"),
                requiredOption(args, "status", "status"),
                optionalOptionOrConfig(args, "target-tenant-id", "NAVI_TARGET_TENANT_ID")));
        return 0;
    }

    private int ensureGrant(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String upstreamUserId = upstreamUserId(args);
        String upstreamUserToken = config.get("NAVI_UPSTREAM_USER_TOKEN");

        GrantUpstreamUserForm form = new GrantUpstreamUserForm();
        form.setUpstreamUserId(upstreamUserId);
        if (hasText(upstreamUserToken)) {
            form.setUpstreamUserToken(upstreamUserToken);
        }
        form.setStatus("ENABLED");

        ClientAppUpstreamUserGrantDTO grant = businessAgentControlApi()
                .grantUpstreamUserAccess(clientAppId, form);
        out.println("ensure-grant ok");
        out.println("clientAppId=" + valueOrEmpty(grant.getClientAppId()));
        out.println("upstreamUserId=" + valueOrEmpty(grant.getUpstreamUserId()));
        out.println("status=" + valueOrEmpty(grant.getStatus()));
        return 0;
    }

    private int verifyAgentReadiness(CliArguments args) {
        AgentReadiness readiness = fetchAgentReadiness(args);
        out.println("verify-agent-readiness " + valueOrEmpty(readiness.getOverallStatus()));
        printAgentReadiness(readiness);
        return "OK".equals(readiness.getOverallStatus()) ? 0 : 2;
    }

    private int ownerSmoke(CliArguments args) {
        out.println("owner-smoke profile=" + (config.profilePath() == null ? "(none)" : config.profilePath()));
        out.println("owner-smoke profileGitIgnored=" + config.profileIsGitIgnored());
        if (!config.profileIsGitIgnored()) {
            throw new UpstreamCliException("Profile path is not git-ignored: " + config.profilePath());
        }

        AgentReadiness readiness = fetchAgentReadiness(args);
        String status = valueOrEmpty(readiness.getOverallStatus());
        out.println("owner-smoke readiness " + status);
        printAgentReadiness(readiness);

        boolean requireDirectory = !args.flag("no-directory-required");
        List<String> missing = missingOwnerSmokeResources(readiness, requireDirectory);
        if (!missing.isEmpty()) {
            out.println("owner-smoke resources FAIL missing=" + String.join(",", missing));
            return 2;
        }
        if (!"OK".equals(readiness.getOverallStatus())) {
            out.println("owner-smoke resources SKIPPED readiness=" + status);
            return 2;
        }

        out.println("owner-smoke resources OK");
        out.println("owner-smoke ready");
        return 0;
    }

    private List<String> missingOwnerSmokeResources(AgentReadiness readiness, boolean requireDirectory) {
        List<String> missing = new ArrayList<>();
        if (!hasText(readiness.getEffectiveModelConfigId())) {
            missing.add("effectiveModelConfigId");
        }
        if (!hasText(readiness.getAgentId())) {
            missing.add("agentId");
        }
        if (!hasText(readiness.getEffectiveWorkerBackend())) {
            missing.add("effectiveWorkerBackend");
        }
        if (requireDirectory && !hasText(readiness.getEffectiveDirectoryId())) {
            missing.add("effectiveDirectoryId");
        }
        if (requireDirectory && !hasText(readiness.getEffectivePhysicalWorkerId())) {
            missing.add("effectivePhysicalWorkerId");
        }
        if (requireDirectory && hasText(readiness.getEffectiveDirectoryId())) {
            String expectedRole = expectedWorkerHostRole(readiness.getEffectiveWorkerBackend());
            String expectedSource = expectedWorkerHostRoleSource(readiness.getEffectiveWorkerBackend());
            if (hasText(expectedRole) && !hasExecutionWorkerRole(readiness, expectedRole, expectedSource)) {
                missing.add("workerRole:" + expectedRole + ":" + expectedSource);
            }
        }
        return missing;
    }

    private String expectedWorkerHostRole(String workerBackend) {
        if ("OPENAI_CODEX".equalsIgnoreCase(valueOrEmpty(workerBackend))) {
            return "codex";
        }
        if ("LANGGRAPH_BIZ".equalsIgnoreCase(valueOrEmpty(workerBackend))) {
            return "biz";
        }
        return null;
    }

    private String expectedWorkerHostRoleSource(String workerBackend) {
        if ("OPENAI_CODEX".equalsIgnoreCase(valueOrEmpty(workerBackend))) {
            return "CLAUDE_WORKER_CODEX_CONFIG";
        }
        if ("LANGGRAPH_BIZ".equalsIgnoreCase(valueOrEmpty(workerBackend))) {
            return "BIZ_WORKER_IDENTITY";
        }
        return null;
    }

    private boolean hasExecutionWorkerRole(AgentReadiness readiness, String expectedRole, String expectedSource) {
        if (readiness.getPhysicalWorkerDiagnostics() == null) {
            return false;
        }
        for (PhysicalWorkerDiagnostic diagnostic : readiness.getPhysicalWorkerDiagnostics()) {
            if (diagnostic != null
                    && expectedRole.equals(valueOrEmpty(diagnostic.getRole()))
                    && expectedSource.equals(valueOrEmpty(diagnostic.getSource()))
                    && Boolean.TRUE.equals(diagnostic.getExecutionWorker())) {
                return true;
            }
        }
        return false;
    }

    private int inspectRuntime(CliArguments args) {
        AgentReadiness readiness = fetchAgentReadiness(args);
        out.println("inspect runtime " + valueOrEmpty(readiness.getOverallStatus()));
        printAgentReadiness(readiness);
        return "OK".equals(readiness.getOverallStatus()) ? 0 : 2;
    }

    private AgentReadiness fetchAgentReadiness(CliArguments args) {
        String agent = agentCode(args);
        String upstreamUserId = upstreamUserId(args);
        return agentApi().verifyReadinessWithClientAppAccessToken(
                agent,
                upstreamUserId,
                modelConfigId(args),
                modelVariant(args),
                optionalOptionOrConfig(args, "directory-id", "NAVI_DIRECTORY_ID"),
                clientAppKey(args),
                clientAppAccessToken(args));
    }

    private void printAgentReadiness(AgentReadiness readiness) {
        out.println("serverTime=" + valueOrEmpty(readiness.getServerTime()));
        out.println("serverTimezone=" + valueOrEmpty(readiness.getServerTimezone()));
        out.println("auditStorageTimezone=" + valueOrEmpty(readiness.getAuditStorageTimezone()));
        out.println("taskIdDateTimezone=" + valueOrEmpty(readiness.getTaskIdDateTimezone()));
        out.println("baseUrl=" + valueOrEmpty(readiness.getBaseUrl()));
        out.println("clientAppId=" + valueOrEmpty(readiness.getClientAppId()));
        out.println("clientAppName=" + redact(readiness.getClientAppName()));
        out.println("agentCode=" + valueOrEmpty(readiness.getAgentCode()));
        out.println("upstreamUserId=" + valueOrEmpty(readiness.getUpstreamUserId()));
        out.println("requestedModelConfigId=" + valueOrEmpty(readiness.getRequestedModelConfigId()));
        out.println("requestedModelVariant=" + valueOrEmpty(readiness.getRequestedModelVariant()));
        out.println("defaultModelConfigId=" + valueOrEmpty(readiness.getDefaultModelConfigId()));
        out.println("defaultModelName=" + valueOrEmpty(readiness.getDefaultModelName()));
        out.println("effectiveModelConfigId=" + valueOrEmpty(readiness.getEffectiveModelConfigId()));
        out.println("effectiveModelName=" + valueOrEmpty(readiness.getEffectiveModelName()));
        out.println("effectiveWorkerBackend=" + valueOrEmpty(readiness.getEffectiveWorkerBackend()));
        out.println("modelConfigSource=" + valueOrEmpty(readiness.getModelConfigSource()));
        out.println("modelCategory=" + valueOrEmpty(readiness.getModelCategory()));
        out.println("agent agentId=" + valueOrEmpty(readiness.getAgentId())
                + " ownerType=" + valueOrEmpty(readiness.getAgentOwnerType())
                + " ownerId=" + valueOrEmpty(readiness.getAgentOwnerId())
                + " source=" + valueOrEmpty(readiness.getAgentSource())
                + " skillId=" + valueOrEmpty(readiness.getSkillId()));
        printPhysicalWorkerReadiness(readiness);
        out.println("internalRoute workerPoolId=" + valueOrEmpty(firstText(readiness.getInternalWorkerPoolId(), readiness.getWorkerPoolId()))
                + " ownerType=" + valueOrEmpty(firstText(readiness.getInternalWorkerPoolOwnerType(), readiness.getWorkerPoolOwnerType()))
                + " ownerId=" + valueOrEmpty(firstText(readiness.getInternalWorkerPoolOwnerId(), readiness.getWorkerPoolOwnerId()))
                + " source=" + valueOrEmpty(firstText(readiness.getInternalWorkerPoolSource(), readiness.getWorkerPoolSource())));
        out.println("workspace requestedDirectoryId=" + valueOrEmpty(readiness.getRequestedDirectoryId())
                + " defaultDirectoryId=" + valueOrEmpty(readiness.getDefaultDirectoryId())
                + " effectiveDirectoryId=" + valueOrEmpty(readiness.getEffectiveDirectoryId())
                + " physicalWorkerId=" + valueOrEmpty(readiness.getEffectivePhysicalWorkerId())
                + " scope=" + valueOrEmpty(readiness.getWorkspaceScope())
                + " resolverType=" + valueOrEmpty(readiness.getWorkspaceResolverType())
                + " readOnly=" + valueOrEmpty(readiness.getWorkspaceReadOnly())
                + " source=" + valueOrEmpty(readiness.getWorkspaceSource()));
        if (readiness.getChecks() != null) {
            for (AgentReadinessCheck check : readiness.getChecks()) {
                out.println("check " + valueOrEmpty(check.getCode())
                        + "=" + valueOrEmpty(check.getStatus())
                        + (hasText(check.getErrorCode()) ? " errorCode=" + valueOrEmpty(check.getErrorCode()) : "")
                        + (hasText(check.getMessage()) ? " message=" + redact(check.getMessage()) : "")
                        + (hasText(check.getAction()) ? " action=" + redact(check.getAction()) : ""));
            }
        }
        if (readiness.getSkillArtifact() != null && readiness.getSkillArtifact().isAvailable()) {
            out.println("skillArtifactTreeUrl=" + valueOrEmpty(readiness.getSkillArtifact().getTreeUrl()));
        }
    }

    private void printPhysicalWorkerReadiness(AgentReadiness readiness) {
        PhysicalWorkerDiagnostic diagnostic = readiness.getPhysicalWorkerDiagnostic();
        String physicalWorkerId = firstText(
                diagnostic != null ? diagnostic.getPhysicalWorkerId() : null,
                readiness.getEffectivePhysicalWorkerId());
        String workerBackend = firstText(
                diagnostic != null ? diagnostic.getWorkerBackend() : null,
                readiness.getEffectiveWorkerBackend());
        String source = firstText(
                diagnostic != null ? diagnostic.getSource() : null,
                readiness.getWorkspaceSource());
        StringBuilder line = new StringBuilder("physicalWorker physicalWorkerId=")
                .append(valueOrEmpty(physicalWorkerId))
                .append(" workerBackend=")
                .append(valueOrEmpty(workerBackend))
                .append(" source=")
                .append(valueOrEmpty(source));
        if (diagnostic != null) {
            line.append(" workerName=").append(redact(diagnostic.getWorkerName()))
                    .append(" baseUrl=").append(redact(diagnostic.getBaseUrl()))
                    .append(" status=").append(valueOrEmpty(diagnostic.getStatus()))
                    .append(" healthStatus=").append(valueOrEmpty(diagnostic.getHealthStatus()))
                    .append(" version=").append(valueOrEmpty(diagnostic.getVersion()))
                    .append(" hostname=").append(redact(diagnostic.getHostname()))
                    .append(" lastHeartbeat=").append(valueOrEmpty(diagnostic.getLastHeartbeat()))
                    .append(" usedAs=").append(workerUsage(diagnostic));
        }
        out.println(line);
        if (readiness.getPhysicalWorkerDiagnostics() != null) {
            for (PhysicalWorkerDiagnostic roleDiagnostic : readiness.getPhysicalWorkerDiagnostics()) {
                if (roleDiagnostic == null) {
                    continue;
                }
                out.println(formatWorkerRoleReadiness(roleDiagnostic));
            }
        }
    }

    private String formatWorkerRoleReadiness(PhysicalWorkerDiagnostic diagnostic) {
        return new StringBuilder("workerRole role=")
                .append(valueOrEmpty(diagnostic.getRole()))
                .append(" physicalWorkerId=")
                .append(valueOrEmpty(diagnostic.getPhysicalWorkerId()))
                .append(" workerBackend=")
                .append(valueOrEmpty(diagnostic.getWorkerBackend()))
                .append(" source=")
                .append(valueOrEmpty(diagnostic.getSource()))
                .append(" workerName=")
                .append(redact(diagnostic.getWorkerName()))
                .append(" baseUrl=")
                .append(redact(diagnostic.getBaseUrl()))
                .append(" status=")
                .append(valueOrEmpty(diagnostic.getStatus()))
                .append(" healthStatus=")
                .append(valueOrEmpty(diagnostic.getHealthStatus()))
                .append(" version=")
                .append(valueOrEmpty(diagnostic.getVersion()))
                .append(" hostname=")
                .append(redact(diagnostic.getHostname()))
                .append(" lastHeartbeat=")
                .append(valueOrEmpty(diagnostic.getLastHeartbeat()))
                .append(" usedAs=")
                .append(workerUsage(diagnostic))
                .toString();
    }

    private String workerUsage(PhysicalWorkerDiagnostic diagnostic) {
        List<String> usages = new ArrayList<>();
        if (Boolean.TRUE.equals(diagnostic.getExecutionWorker())) {
            usages.add("execution");
        }
        if (Boolean.TRUE.equals(diagnostic.getDirectoryWorker())) {
            usages.add("directory");
        }
        return usages.isEmpty() ? "(empty)" : String.join(",", usages);
    }

    private int ask(CliArguments args) {
        String agent = agentCode(args);
        String upstreamUserId = upstreamUserId(args);
        String message = requiredOption(args, "message", "message");
        Map<String, Object> clientContext = parseClientContext(args);
        Map<String, Object> runtimeOptions = buildAskRuntimeOptions(args);
        String clientRequestId = java.util.UUID.randomUUID().toString();
        out.println("clientRequestId=" + clientRequestId);
        AgentTask task;
        try {
            task = agentApi().askWithClientAppAccessToken(
                    agent,
                    message,
                    args.option("context-id"),
                    parseInteger(args.option("max-turns")),
                    clientContext,
                    modelConfigId(args),
                    modelVariant(args),
                    null,
                    runtimeOptions,
                    clientAppKey(args),
                    clientAppAccessToken(args),
                    upstreamUserId,
                    clientRequestId);
        } catch (NavigatorApiException e) {
            throw actionableAskException(e);
        }
        printTask(task);
        return 0;
    }

    private UpstreamCliException actionableAskException(NavigatorApiException e) {
        String message = e.getMessage();
        if (message != null && message.contains("TASK_DIRECTORY_REQUIRED")) {
            return new UpstreamCliException(message
                    + ". Multiple or ambiguous Actor-owned directories may be available; rerun upstream ask with --directory-id <id>.");
        }
        return new UpstreamCliException(message != null ? message : "Navigator ask request failed", e);
    }

    private Map<String, Object> buildAskRuntimeOptions(CliArguments args) {
        Map<String, Object> options = new LinkedHashMap<>();
        putText(options, "providerType", optionalOptionOrConfig(args, "provider-type", "NAVI_PROVIDER_TYPE"));
        putText(options, "directoryId", optionalOptionOrConfig(args, "directory-id", "NAVI_DIRECTORY_ID"));
        putText(options, "codexHomeKey", optionalOptionOrConfig(args, "codex-home-key", "NAVI_CODEX_HOME_KEY"));
        putText(options, "privateAccountId", optionalOptionOrConfig(args, "private-account-id", "NAVI_PRIVATE_ACCOUNT_ID"));
        putText(options, "sandboxMode", optionalOptionOrConfig(args, "sandbox-mode", "NAVI_CODEX_SANDBOX_MODE"));
        putText(options, "approvalPolicy", optionalOptionOrConfig(args, "approval-policy", "NAVI_CODEX_APPROVAL_POLICY"));
        putText(options, "webSearchMode", optionalOptionOrConfig(args, "web-search-mode", "NAVI_CODEX_WEB_SEARCH_MODE"));
        putOptionalCsv(options, args, "allowedTools", "allowed-tools", "NAVI_ALLOWED_TOOLS");
        putOptionalCsv(options, args, "allowedFunctions", "allowed-functions", "NAVI_ALLOWED_FUNCTIONS");
        // STANDARD runtime requests default to an explicit empty model-tool and
        // BusinessFunction surface so admission facts are deterministic and
        // task-token scope cannot silently inherit a broader runtime default.
        options.putIfAbsent("allowedTools", List.of());
        options.putIfAbsent("allowedFunctions", List.of());
        Boolean networkAccessEnabled = optionalBooleanOptionOrConfig(
                args, "network-access-enabled", "NAVI_CODEX_NETWORK_ACCESS_ENABLED");
        if (networkAccessEnabled != null) {
            options.put("networkAccessEnabled", networkAccessEnabled);
        }
        return options;
    }

    private int messages(CliArguments args) throws InterruptedException {
        String agent = explicitAgentCode(args, "messages");
        String taskId = requiredOption(args, "task-id", "task id");
        String upstreamUserId = optionalUpstreamUserId(args);
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
            printTaskDiagnostics(page, task);
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

    private int diagnostics(CliArguments args) {
        String agent = explicitAgentCode(args, "diagnostics");
        String taskId = requiredOption(args, "task-id", "task id");
        String upstreamUserId = optionalUpstreamUserId(args);
        TaskDiagnostics diagnostics = agentApi().getTaskDiagnosticsWithClientAppAccessToken(
                agent, taskId, clientAppKey(args), clientAppAccessToken(args), upstreamUserId);
        printTaskDiagnostics(diagnostics);
        return 0;
    }

    private int diagnosticsSessionDir(CliArguments args) {
        if (args.flag("help")) {
            return diagnosticsUsage();
        }
        String contextId = requiredOption(args, "context-id", "context id");
        String taskId = args.option("task-id");
        SessionDirectoryDiagnostics diagnostics = resolveSessionDirectoryDiagnostics(args, contextId, taskId);
        printSessionDirectoryDiagnostics(diagnostics);
        return 0;
    }

    private SessionDirectoryDiagnostics resolveSessionDirectoryDiagnostics(CliArguments args,
                                                                          String contextId,
                                                                          String taskId) {
        String hostname = firstNonBlank(localHostname(), env.get("COMPUTERNAME"), env.get("HOSTNAME"));
        String workerHost = firstNonBlank(
                args.option("worker-host"),
                env.get("NAVI_WORKER_HOST"),
                env.get("BIZ_WORKER_HOST"),
                config.get("NAVI_WORKER_HOST"),
                config.get("BIZ_WORKER_HOST"),
                hostname);
        String workerBackend = firstNonBlank(
                args.option("worker-backend"),
                env.get("NAVI_WORKER_BACKEND"),
                config.get("NAVI_WORKER_BACKEND"),
                LANGGRAPH_BIZ_BACKEND);
        String physicalWorkerId = firstNonBlank(
                args.option("physical-worker-id"),
                args.option("worker-id"),
                env.get("NAVI_PHYSICAL_WORKER_ID"),
                env.get("NAVI_BIZ_WORKER_ID"),
                env.get("NAVI_WORKER_ID"),
                config.get("NAVI_PHYSICAL_WORKER_ID"),
                config.get("NAVI_BIZ_WORKER_ID"),
                config.get("NAVI_WORKER_ID"));

        if (isOpenAiCodexBackend(workerBackend)) {
            return resolveCodexSessionDiagnostics(
                    args, contextId, taskId, workerBackend, physicalWorkerId, workerHost, hostname);
        }

        ContextLocator locator = parseContextLocator(contextId);
        if (locator == null) {
            return new SessionDirectoryDiagnostics(contextId, taskId, null, false, workerBackend, physicalWorkerId,
                    workerHost, hostname, null, null, null, null, null, null, null,
                    null, "langgraph-session", "unavailable", "context-not-found");
        }

        List<Path> dataRoots = candidateBizWorkerDataRoots(args);
        Path selectedSessionDirectory = null;
        Path expectedSessionDirectory = null;
        for (Path dataRoot : dataRoots) {
            Path candidateSessionDirectory = bizWorkerSessionDirectory(dataRoot, locator);
            if (expectedSessionDirectory == null) {
                expectedSessionDirectory = candidateSessionDirectory;
            }
            if (Files.isDirectory(candidateSessionDirectory)) {
                selectedSessionDirectory = candidateSessionDirectory;
                break;
            }
        }

        boolean exists = selectedSessionDirectory != null;
        Path sessionDirectory = exists ? selectedSessionDirectory : expectedSessionDirectory;
        Path logsDirectory = sessionDirectory != null ? sessionDirectory.resolve("logs").toAbsolutePath().normalize() : null;
        Path skillToolCallsDirectory = logsDirectory != null
                ? logsDirectory.resolve("skill-tool-calls").toAbsolutePath().normalize()
                : null;
        Path skillToolCallsFile = hasText(taskId) && skillToolCallsDirectory != null
                ? skillToolCallsDirectory.resolve(safePathSegment(taskId) + ".jsonl").toAbsolutePath().normalize()
                : null;
        Path runtimeMessageEventsDirectory = logsDirectory != null
                ? logsDirectory.resolve("runtime-message-events").toAbsolutePath().normalize()
                : null;
        Path runtimeMessageEventsFile = hasText(taskId) && runtimeMessageEventsDirectory != null
                ? runtimeMessageEventsDirectory.resolve(safePathSegment(taskId) + ".jsonl").toAbsolutePath().normalize()
                : null;
        Path llmSubmissionsDirectory = logsDirectory != null
                ? logsDirectory.resolve("llm-submissions").toAbsolutePath().normalize()
                : null;
        String notFoundReason = exists ? null : sessionDirectoryNotFoundReason(locator, dataRoots);
        String accessHint = exists
                ? "local"
                : (!isLikelyLocalHost(workerHost, hostname) ? "ssh-required" : "unavailable");
        return new SessionDirectoryDiagnostics(contextId, taskId, null, exists, workerBackend, physicalWorkerId,
                workerHost, hostname, sessionDirectory, logsDirectory, skillToolCallsDirectory, skillToolCallsFile,
                runtimeMessageEventsDirectory, runtimeMessageEventsFile, llmSubmissionsDirectory,
                null, "langgraph-session", accessHint, notFoundReason);
    }

    private SessionDirectoryDiagnostics resolveCodexSessionDiagnostics(CliArguments args,
                                                                       String contextId,
                                                                       String taskId,
                                                                       String workerBackend,
                                                                       String physicalWorkerId,
                                                                       String workerHost,
                                                                       String hostname) {
        String providerTaskId = firstNonBlank(
                args.option("provider-task-id"),
                args.option("codex-provider-task-id"),
                taskId);
        Path debugLogFile = null;
        boolean exists = false;
        String notFoundReason = "business-mcp-debug-log-task-id-missing";
        if (hasText(providerTaskId)) {
            Path workspaceRoot = candidateCodexWorkspaceRoots(args).stream()
                    .findFirst()
                    .orElse(cwd.toAbsolutePath().normalize());
            debugLogFile = codexBusinessMcpDebugLogFile(workspaceRoot, providerTaskId);
            exists = Files.isRegularFile(debugLogFile);
            notFoundReason = exists ? null : "business-mcp-debug-log-not-found";
        }
        String accessHint = exists
                ? "local"
                : (!isLikelyLocalHost(workerHost, hostname) ? "ssh-required" : "unavailable");
        return new SessionDirectoryDiagnostics(contextId, taskId, providerTaskId, exists, workerBackend, physicalWorkerId,
                workerHost, hostname, null, null, null, null, null, null, null,
                debugLogFile, "codex-business-mcp", accessHint, notFoundReason);
    }

    private boolean isOpenAiCodexBackend(String workerBackend) {
        return OPENAI_CODEX_BACKEND.equalsIgnoreCase(valueOrEmpty(workerBackend))
                || "codex-biz-worker".equalsIgnoreCase(valueOrEmpty(workerBackend));
    }

    private List<Path> candidateCodexWorkspaceRoots(CliArguments args) {
        List<Path> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addDataRootCandidate(candidates, seen, args.option("codex-workspace-root"), cwd);
        addDataRootCandidate(candidates, seen, args.option("workspace-root"), cwd);
        addDataRootCandidate(candidates, seen, env.get("NAVI_CODEX_WORKSPACE_ROOT"), cwd);
        addDataRootCandidate(candidates, seen, env.get("CODEX_WORKSPACE_ROOT"), cwd);
        addDataRootCandidate(candidates, seen, config.get("NAVI_CODEX_WORKSPACE_ROOT"), cwd);
        addDataRootCandidate(candidates, seen, config.get("CODEX_WORKSPACE_ROOT"), cwd);
        addDataRootPathCandidate(candidates, seen, cwd);
        return candidates;
    }

    private Path codexBusinessMcpDebugLogFile(Path workspaceRoot, String providerTaskId) {
        return workspaceRoot
                .resolve("temp")
                .resolve("codex-worker-3070")
                .resolve("business-mcp-" + safePathSegment(providerTaskId) + ".log")
                .toAbsolutePath()
                .normalize();
    }

    private List<Path> candidateBizWorkerDataRoots(CliArguments args) {
        List<Path> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addDataRootCandidate(candidates, seen, args.option("data-root"), cwd);
        addDataRootCandidate(candidates, seen, args.option("biz-worker-data-root"), cwd);
        addDataRootCandidate(candidates, seen, env.get("NAVI_BIZ_WORKER_DATA_ROOT"), cwd);
        addDataRootCandidate(candidates, seen, env.get("BIZ_WORKER_DATA_ROOT"), cwd);
        addDataRootCandidate(candidates, seen, config.get("NAVI_BIZ_WORKER_DATA_ROOT"), cwd);
        addDataRootCandidate(candidates, seen, config.get("BIZ_WORKER_DATA_ROOT"), cwd);

        addBizWorkerEnvFileDataRootCandidates(candidates, seen, args.option("biz-worker-env-file"));
        addBizWorkerEnvFileDataRootCandidates(candidates, seen, env.get("NAVI_BIZ_WORKER_ENV_FILE"));
        addBizWorkerEnvFileDataRootCandidates(candidates, seen, env.get("BIZ_WORKER_ENV_FILE"));
        addBizWorkerEnvFileDataRootCandidates(candidates, seen, config.get("NAVI_BIZ_WORKER_ENV_FILE"));
        addDefaultBizWorkerEnvFileDataRootCandidates(candidates, seen);
        addDefaultBizWorkerDataRootCandidates(candidates, seen);
        return candidates;
    }

    private void addDefaultBizWorkerEnvFileDataRootCandidates(List<Path> candidates, Set<String> seen) {
        List<Path> workerDirs = new ArrayList<>();
        workerDirs.add(cwd.resolve("tools").resolve("langgraph-biz-worker").toAbsolutePath().normalize());
        if (cwd.getFileName() != null && "langgraph-biz-worker".equals(cwd.getFileName().toString())) {
            workerDirs.add(cwd.toAbsolutePath().normalize());
        }
        for (Path workerDir : workerDirs) {
            for (String fileName : List.of(".env", ".env.local", ".env.real-llm.local",
                    ".env.mock-llm.local", ".env.qwen35-plus.local")) {
                addEnvFileDataRootCandidates(candidates, seen, workerDir.resolve(fileName));
            }
        }
    }

    private void addDefaultBizWorkerDataRootCandidates(List<Path> candidates, Set<String> seen) {
        Path cursor = cwd.toAbsolutePath().normalize();
        int depth = 0;
        while (cursor != null && depth++ < 6) {
            addDataRootPathCandidate(candidates, seen,
                    cursor.resolve("tools").resolve("langgraph-biz-worker").resolve("data"));
            cursor = cursor.getParent();
        }
        if (cwd.getFileName() != null && "langgraph-biz-worker".equals(cwd.getFileName().toString())) {
            addDataRootPathCandidate(candidates, seen, cwd.resolve("data"));
        }
    }

    private void addBizWorkerEnvFileDataRootCandidates(List<Path> candidates, Set<String> seen, String envFileValue) {
        if (!hasText(envFileValue)) {
            return;
        }
        Path envFile = resolveCliPath(envFileValue, cwd);
        addEnvFileDataRootCandidates(candidates, seen, envFile);
    }

    private void addEnvFileDataRootCandidates(List<Path> candidates, Set<String> seen, Path envFile) {
        if (envFile == null) {
            return;
        }
        Path baseDir = envFile.getParent() != null ? envFile.getParent() : cwd;
        addDataRootCandidate(candidates, seen, readEnvFileValue(envFile, "BIZ_WORKER_DATA_ROOT"), baseDir);
        addDataRootCandidate(candidates, seen, readEnvFileValue(envFile, "NAVI_BIZ_WORKER_DATA_ROOT"), baseDir);
    }

    private void addDataRootCandidate(List<Path> candidates, Set<String> seen, String value, Path baseDir) {
        if (!hasText(value)) {
            return;
        }
        addDataRootPathCandidate(candidates, seen, resolveCliPath(stripOptionalQuotes(value.trim()), baseDir));
    }

    private void addDataRootPathCandidate(List<Path> candidates, Set<String> seen, Path path) {
        if (path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (seen.add(normalized.toString())) {
            candidates.add(normalized);
        }
    }

    private Path resolveCliPath(String value, Path baseDir) {
        if (!hasText(value)) {
            return null;
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = (baseDir != null ? baseDir : cwd).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private String readEnvFileValue(Path envFile, String key) {
        if (envFile == null || !Files.isRegularFile(envFile)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!hasText(trimmed) || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("export ")) {
                    trimmed = trimmed.substring("export ".length()).trim();
                }
                int equals = trimmed.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String name = trimmed.substring(0, equals).trim();
                if (key.equals(name)) {
                    return stripOptionalQuotes(trimmed.substring(equals + 1).trim());
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private String stripOptionalQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private ContextLocator parseContextLocator(String contextId) {
        if (!hasText(contextId)) {
            return null;
        }
        Matcher matcher = BIZ_CONTEXT_ID_PATTERN.matcher(contextId);
        if (!matcher.matches()) {
            return null;
        }
        String compactDate = matcher.group(1);
        try {
            LocalDate date = LocalDate.parse(compactDate, BIZ_CONTEXT_DATE_FORMATTER);
            return new ContextLocator(
                    contextId,
                    compactDate.substring(0, 4),
                    compactDate.substring(4, 6),
                    compactDate.substring(6, 8),
                    matcher.group(2).toLowerCase(Locale.ROOT),
                    date);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Path bizWorkerSessionDirectory(Path dataRoot, ContextLocator locator) {
        return dataRoot
                .resolve("runtime")
                .resolve("sessions")
                .resolve("by-date")
                .resolve(locator.year())
                .resolve(locator.month())
                .resolve(locator.day())
                .resolve(locator.shard())
                .resolve(safePathSegment(locator.contextId()))
                .toAbsolutePath()
                .normalize();
    }

    private String sessionDirectoryNotFoundReason(ContextLocator locator, List<Path> dataRoots) {
        boolean anySessionsRootExists = false;
        boolean anyDateDirExists = false;
        boolean anyShardDirExists = false;
        for (Path dataRoot : dataRoots) {
            Path byDate = dataRoot.resolve("runtime").resolve("sessions").resolve("by-date");
            if (Files.isDirectory(byDate)) {
                anySessionsRootExists = true;
            }
            Path dateDir = byDate.resolve(locator.year()).resolve(locator.month()).resolve(locator.day());
            if (Files.isDirectory(dateDir)) {
                anyDateDirExists = true;
            }
            if (Files.isDirectory(dateDir.resolve(locator.shard()))) {
                anyShardDirExists = true;
            }
        }
        if (!anySessionsRootExists) {
            return "worker-unavailable";
        }
        if (!anyDateDirExists && locator.date().isBefore(LocalDate.now())) {
            return "cleaned";
        }
        if (anyDateDirExists || anyShardDirExists) {
            return "context-not-found";
        }
        return "session-dir-not-found";
    }

    private void printSessionDirectoryDiagnostics(SessionDirectoryDiagnostics diagnostics) {
        out.println("contextId=" + valueOrEmpty(diagnostics.contextId()));
        if (hasText(diagnostics.taskId())) {
            out.println("taskId=" + valueOrEmpty(diagnostics.taskId()));
        }
        if (hasText(diagnostics.providerTaskId())) {
            out.println("providerTaskId=" + valueOrEmpty(diagnostics.providerTaskId()));
        }
        out.println("exists=" + diagnostics.exists());
        out.println("workerBackend=" + valueOrEmpty(diagnostics.workerBackend()));
        out.println("diagnosticMode=" + valueOrEmpty(diagnostics.diagnosticMode()));
        out.println("physicalWorkerId=" + valueOrEmpty(diagnostics.physicalWorkerId()));
        out.println("workerHost=" + redact(valueOrEmpty(diagnostics.workerHost())));
        out.println("hostname=" + redact(valueOrEmpty(diagnostics.hostname())));
        out.println("sessionDirectory=" + valueOrEmpty(diagnostics.sessionDirectory()));
        out.println("logsDirectory=" + valueOrEmpty(diagnostics.logsDirectory()));
        out.println("skillToolCallsDirectory=" + valueOrEmpty(diagnostics.skillToolCallsDirectory()));
        if (hasText(diagnostics.taskId())) {
            out.println("skillToolCallsFile=" + valueOrEmpty(diagnostics.skillToolCallsFile()));
        }
        out.println("runtimeMessageEventsDirectory=" + valueOrEmpty(diagnostics.runtimeMessageEventsDirectory()));
        if (hasText(diagnostics.taskId())) {
            out.println("runtimeMessageEventsFile=" + valueOrEmpty(diagnostics.runtimeMessageEventsFile()));
        }
        out.println("llmSubmissionsDirectory=" + valueOrEmpty(diagnostics.llmSubmissionsDirectory()));
        out.println("businessMcpDebugLogFile=" + valueOrEmpty(diagnostics.businessMcpDebugLogFile()));
        out.println("accessHint=" + valueOrEmpty(diagnostics.accessHint()));
        if (!diagnostics.exists()) {
            out.println("notFoundReason=" + valueOrEmpty(diagnostics.notFoundReason()));
        }
    }

    private String localHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isLikelyLocalHost(String workerHost, String hostname) {
        String normalized = normalizeHostForCompare(workerHost);
        if (!hasText(normalized)) {
            return true;
        }
        if ("localhost".equals(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized)) {
            return true;
        }
        for (String candidate : new String[]{hostname, env.get("COMPUTERNAME"), env.get("HOSTNAME")}) {
            if (normalized.equals(normalizeHostForCompare(candidate))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeHostForCompare(String value) {
        if (!hasText(value)) {
            return "";
        }
        String host = value.trim();
        if (host.startsWith("http://") || host.startsWith("https://")) {
            try {
                host = URI.create(host).getHost();
            } catch (IllegalArgumentException ignored) {
                // Fall back to string normalization below.
            }
        }
        if (!hasText(host)) {
            return "";
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int colon = host.indexOf(':');
        if (colon > 0 && colon == host.lastIndexOf(':')) {
            host = host.substring(0, colon);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private static String safePathSegment(String value) {
        if (!hasText(value)) {
            return "unknown";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '.' || ch == '_' || ch == '-') {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private int evidence(CliArguments args) throws Exception {
        String agent = explicitAgentCode(args, "evidence");
        String taskId = requiredOption(args, "task-id", "task id");
        String upstreamUserId = optionalUpstreamUserId(args);
        TaskEvidence evidence = agentApi().getTaskEvidenceWithClientAppAccessToken(
                agent, taskId, clientAppKey(args), clientAppAccessToken(args), upstreamUserId);
        printTaskEvidence(evidence);
        return 0;
    }

    private int sessions(CliArguments args) {
        SessionListPage page = agentApi().listBusinessAgentSessionsWithClientAppAccessToken(
                parseInteger(args.option("limit"), 20), args.option("cursor"),
                clientAppKey(args), clientAppAccessToken(args), upstreamUserId(args));
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
        String contextId = requiredOption(args, "context-id", "context id");
        SessionMessagesPage page = agentApi().getBusinessAgentSessionMessagesWithClientAppAccessToken(
                contextId, parseInteger(args.option("limit"), 50), args.option("cursor"),
                clientAppKey(args), clientAppAccessToken(args), upstreamUserId(args));
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
        SkillBundleDTO dto;
        String normalizedScope = normalizeSkillBundleScope(scope);
        if ("ACCOUNT_PRIVATE".equals(normalizedScope)) {
            SyncAccountSkillBundleForm form = objectMapper.readValue(json, SyncAccountSkillBundleForm.class);
            String upstreamUserId = upstreamUserId(args);
            dto = agentApi().syncMyAccountSkillBundleWithClientAppAccessToken(
                    form,
                    clientAppKey(args),
                    clientAppAccessToken(args),
                    upstreamUserId);
        } else {
            SyncSkillBundleForm form = objectMapper.readValue(json, SyncSkillBundleForm.class);
            form.setScope("CLIENT_APP_PUBLIC");
            if (!hasText(form.getClientAppId())) {
                form.setClientAppId(requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id"));
            }
            dto = businessAgentControlApi().syncSkillBundle(form);
        }
        printSkillBundle(dto);
        return 0;
    }

    private int skillClearPublic(CliArguments args) {
        ClearSkillBundleForm form = buildSkillClearForm(args, false);
        SkillClearResultDTO result = businessAgentControlApi().clearPublicSkillBundles(form);
        printSkillClearResult("skill clear-public", result);
        return 0;
    }

    private int skillClearAccount(CliArguments args) {
        ClearSkillBundleForm form = buildSkillClearForm(args, true);
        SkillClearResultDTO result = businessAgentControlApi().clearAccountSkillBundles(form);
        printSkillClearResult("skill clear-account", result);
        return 0;
    }

    private ClearSkillBundleForm buildSkillClearForm(CliArguments args, boolean accountScope) {
        boolean dryRun = args.flag("dry-run");
        if (!dryRun && !args.flag("yes")) {
            throw new UpstreamCliException("skill clear requires --dry-run or --yes");
        }
        ClearSkillBundleForm form = new ClearSkillBundleForm();
        form.setClientAppId(requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id"));
        form.setSkillId(args.option("skill-id"));
        form.setDryRun(dryRun);
        if (accountScope) {
            form.setAccountId(requiredOption(args, "account-id", "account id"));
        }
        return form;
    }

    private int agentSync(CliArguments args) throws Exception {
        String manifest = requiredOption(args, "manifest", "manifest path");
        Path manifestPath = cwd.resolve(manifest).normalize();
        if (!Files.isRegularFile(manifestPath)) {
            throw new UpstreamCliException("manifest file not found: " + manifestPath);
        }
        String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
        SyncBusinessAgentBundleForm form = objectMapper.readValue(json, SyncBusinessAgentBundleForm.class);
        if (!hasText(form.getClientAppId())) {
            form.setClientAppId(requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id"));
        }

        BusinessAgentBundleDTO dto = businessAgentControlApi().syncBusinessAgentBundle(form);
        printBusinessAgentBundle(dto);
        return 0;
    }

    private int agentSystemList(CliArguments args) throws Exception {
        List<BusinessAgentBundleDTO> agents = upstreamAdminApi()
                .listUpstreamSystemAgents(args.option("target-tenant-id"));
        out.println("agentCount=" + (agents != null ? agents.size() : 0));
        printJson(agents);
        return 0;
    }

    private int agentSystemCreate(CliArguments args) throws Exception {
        UpstreamAgentForm form = readJsonFile(requiredOption(args, "file", "agent json file"), UpstreamAgentForm.class);
        BusinessAgentBundleDTO dto = upstreamAdminApi().createUpstreamSystemAgent(form, args.option("target-tenant-id"));
        out.println("agent system-create ok");
        printBusinessAgentBundle(dto);
        return 0;
    }

    private int agentSystemGet(CliArguments args) {
        BusinessAgentBundleDTO dto = upstreamAdminApi()
                .getUpstreamSystemAgent(agentCode(args), args.option("target-tenant-id"));
        printBusinessAgentBundle(dto);
        return 0;
    }

    private int agentSystemUpdate(CliArguments args) throws Exception {
        UpstreamAgentForm form = readJsonFile(requiredOption(args, "file", "agent json file"), UpstreamAgentForm.class);
        BusinessAgentBundleDTO dto = upstreamAdminApi()
                .updateUpstreamSystemAgent(agentCode(args), form, args.option("target-tenant-id"));
        out.println("agent system-update ok");
        printBusinessAgentBundle(dto);
        return 0;
    }

    private int agentModelBindings(CliArguments args) throws Exception {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        List<AgentModelBindingDTO> bindings = businessAgentControlApi()
                .listAgentModelBindings(clientAppId, agentCode(args));
        out.println("agentModelBindingCount=" + (bindings != null ? bindings.size() : 0));
        printJson(bindings);
        return 0;
    }

    private int agentBindModel(CliArguments args) throws Exception {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        AgentModelBindingDTO binding = businessAgentControlApi().bindAgentModel(clientAppId, agentCode(args), bindAgentModelForm(args));
        out.println("agent bind-model ok");
        printJson(binding);
        return 0;
    }

    private int agentUnbindModel(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        businessAgentControlApi().unbindAgentModel(clientAppId, agentCode(args), modelConfigId);
        out.println("agent unbind-model ok");
        out.println("agent=" + agentCode(args));
        out.println("modelConfigId=" + modelConfigId);
        return 0;
    }

    private int agentSetDefaultModel(CliArguments args) throws Exception {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        BindAgentModelForm form = bindAgentModelForm(args);
        AgentModelBindingDTO binding = businessAgentControlApi().setDefaultAgentModel(clientAppId, agentCode(args), form);
        out.println("agent set-default-model ok");
        printJson(binding);
        return 0;
    }

    private int agentSystemModelBindings(CliArguments args) throws Exception {
        List<AgentModelBindingDTO> bindings = upstreamAdminApi()
                .listUpstreamSystemAgentModelBindings(agentCode(args), args.option("target-tenant-id"));
        out.println("agentModelBindingCount=" + (bindings != null ? bindings.size() : 0));
        printJson(bindings);
        return 0;
    }

    private int agentSystemBindModel(CliArguments args) throws Exception {
        AgentModelBindingDTO binding = upstreamAdminApi().bindUpstreamSystemAgentModel(
                agentCode(args),
                bindAgentModelForm(args),
                args.option("target-tenant-id"));
        out.println("agent system-bind-model ok");
        printJson(binding);
        return 0;
    }

    private int agentSystemUnbindModel(CliArguments args) {
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        upstreamAdminApi().unbindUpstreamSystemAgentModel(agentCode(args), modelConfigId, args.option("target-tenant-id"));
        out.println("agent system-unbind-model ok");
        out.println("agent=" + agentCode(args));
        out.println("modelConfigId=" + modelConfigId);
        return 0;
    }

    private int agentSystemSetDefaultModel(CliArguments args) throws Exception {
        AgentModelBindingDTO binding = upstreamAdminApi().setDefaultUpstreamSystemAgentModel(
                agentCode(args),
                bindAgentModelForm(args),
                args.option("target-tenant-id"));
        out.println("agent system-set-default-model ok");
        printJson(binding);
        return 0;
    }

    private BindAgentModelForm bindAgentModelForm(CliArguments args) {
        BindAgentModelForm form = new BindAgentModelForm();
        form.setModelConfigId(requiredOption(args, "model-config-id", "model config id"));
        return form;
    }

    private int agentWorkspaceBindings(CliArguments args) throws Exception {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        List<AgentWorkspaceBindingDTO> bindings = businessAgentControlApi()
                .listAgentWorkspaceBindings(clientAppId, agentCode(args));
        out.println("agentWorkspaceBindingCount=" + (bindings != null ? bindings.size() : 0));
        printJson(bindings);
        return 0;
    }

    private int agentBindWorkspace(CliArguments args) throws Exception {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        AgentWorkspaceBindingDTO binding = businessAgentControlApi().bindAgentWorkspace(
                clientAppId,
                agentCode(args),
                bindAgentWorkspaceForm(args));
        out.println("agent bind-workspace ok");
        printJson(binding);
        return 0;
    }

    private int agentUnbindWorkspace(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String directoryId = requiredOption(args, "directory-id", "directory id");
        businessAgentControlApi().unbindAgentWorkspace(clientAppId, agentCode(args), directoryId);
        out.println("agent unbind-workspace ok");
        out.println("agent=" + agentCode(args));
        out.println("directoryId=" + directoryId);
        return 0;
    }

    private int agentSetDefaultWorkspace(CliArguments args) throws Exception {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        AgentWorkspaceBindingDTO binding = businessAgentControlApi().setDefaultAgentWorkspace(
                clientAppId,
                agentCode(args),
                bindAgentWorkspaceForm(args));
        out.println("agent set-default-workspace ok");
        printJson(binding);
        return 0;
    }

    private int agentSystemWorkspaceBindings(CliArguments args) throws Exception {
        List<AgentWorkspaceBindingDTO> bindings = upstreamAdminApi()
                .listUpstreamSystemAgentWorkspaceBindings(agentCode(args), args.option("target-tenant-id"));
        out.println("agentWorkspaceBindingCount=" + (bindings != null ? bindings.size() : 0));
        printJson(bindings);
        return 0;
    }

    private int agentSystemBindWorkspace(CliArguments args) throws Exception {
        AgentWorkspaceBindingDTO binding = upstreamAdminApi().bindUpstreamSystemAgentWorkspace(
                agentCode(args),
                bindAgentWorkspaceForm(args),
                args.option("target-tenant-id"));
        out.println("agent system-bind-workspace ok");
        printJson(binding);
        return 0;
    }

    private int agentSystemUnbindWorkspace(CliArguments args) {
        String directoryId = requiredOption(args, "directory-id", "directory id");
        upstreamAdminApi().unbindUpstreamSystemAgentWorkspace(agentCode(args), directoryId, args.option("target-tenant-id"));
        out.println("agent system-unbind-workspace ok");
        out.println("agent=" + agentCode(args));
        out.println("directoryId=" + directoryId);
        return 0;
    }

    private int agentSystemSetDefaultWorkspace(CliArguments args) throws Exception {
        AgentWorkspaceBindingDTO binding = upstreamAdminApi().setDefaultUpstreamSystemAgentWorkspace(
                agentCode(args),
                bindAgentWorkspaceForm(args),
                args.option("target-tenant-id"));
        out.println("agent system-set-default-workspace ok");
        printJson(binding);
        return 0;
    }

    private BindAgentWorkspaceForm bindAgentWorkspaceForm(CliArguments args) {
        BindAgentWorkspaceForm form = new BindAgentWorkspaceForm();
        form.setDirectoryId(requiredOption(args, "directory-id", "directory id"));
        return form;
    }

    private int agentWorkerBindings(CliArguments args) throws Exception {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        List<AgentWorkerBindingDTO> bindings = businessAgentControlApi()
                .listAgentWorkerBindings(clientAppId, agentCode(args));
        out.println("agentWorkerBindingCount=" + (bindings != null ? bindings.size() : 0));
        printJson(bindings);
        return 0;
    }

    private int agentBindWorker(CliArguments args) throws Exception {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        AgentWorkerBindingDTO binding = businessAgentControlApi().bindAgentWorker(
                clientAppId,
                agentCode(args),
                bindAgentWorkerForm(args));
        out.println("agent bind-worker ok");
        printJson(binding);
        return 0;
    }

    private int agentUnbindWorker(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String workerPoolId = requiredOption(args, "worker-pool-id", "worker pool id");
        businessAgentControlApi().unbindAgentWorker(clientAppId, agentCode(args), workerPoolId);
        out.println("agent unbind-worker ok");
        out.println("agent=" + agentCode(args));
        out.println("workerPoolId=" + workerPoolId);
        return 0;
    }

    private int agentSetDefaultWorker(CliArguments args) throws Exception {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        AgentWorkerBindingDTO binding = businessAgentControlApi().setDefaultAgentWorker(
                clientAppId,
                agentCode(args),
                bindAgentWorkerForm(args));
        out.println("agent set-default-worker ok");
        printJson(binding);
        return 0;
    }

    private int agentSystemWorkerBindings(CliArguments args) throws Exception {
        List<AgentWorkerBindingDTO> bindings = upstreamAdminApi()
                .listUpstreamSystemAgentWorkerBindings(agentCode(args), args.option("target-tenant-id"));
        out.println("agentWorkerBindingCount=" + (bindings != null ? bindings.size() : 0));
        printJson(bindings);
        return 0;
    }

    private int agentSystemBindWorker(CliArguments args) throws Exception {
        AgentWorkerBindingDTO binding = upstreamAdminApi().bindUpstreamSystemAgentWorker(
                agentCode(args),
                bindAgentWorkerForm(args),
                args.option("target-tenant-id"));
        out.println("agent system-bind-worker ok");
        printJson(binding);
        return 0;
    }

    private int agentSystemUnbindWorker(CliArguments args) {
        String workerPoolId = requiredOption(args, "worker-pool-id", "worker pool id");
        upstreamAdminApi().unbindUpstreamSystemAgentWorker(agentCode(args), workerPoolId, args.option("target-tenant-id"));
        out.println("agent system-unbind-worker ok");
        out.println("agent=" + agentCode(args));
        out.println("workerPoolId=" + workerPoolId);
        return 0;
    }

    private int agentSystemSetDefaultWorker(CliArguments args) throws Exception {
        AgentWorkerBindingDTO binding = upstreamAdminApi().setDefaultUpstreamSystemAgentWorker(
                agentCode(args),
                bindAgentWorkerForm(args),
                args.option("target-tenant-id"));
        out.println("agent system-set-default-worker ok");
        printJson(binding);
        return 0;
    }

    private BindAgentWorkerForm bindAgentWorkerForm(CliArguments args) {
        BindAgentWorkerForm form = new BindAgentWorkerForm();
        form.setWorkerPoolId(requiredOption(args, "worker-pool-id", "worker pool id"));
        return form;
    }

    private int functionImport(CliArguments args) throws Exception {
        ImportBusinessFunctionManifestForm form = readJsonFile(
                requiredOption(args, "manifest", "manifest path"),
                ImportBusinessFunctionManifestForm.class);
        if (!hasText(form.getFunctionId())) {
            throw new UpstreamCliException("function manifest requires functionId");
        }
        if (!hasText(form.getVersion())) {
            throw new UpstreamCliException("function manifest requires version");
        }
        businessAgentControlApi().importBusinessFunctionManifest(form);
        out.println("function import ok");
        out.println("functionId=" + valueOrEmpty(form.getFunctionId()));
        out.println("version=" + valueOrEmpty(form.getVersion()));
        out.println("status=" + valueOrEmpty(form.getStatus()));
        return 0;
    }

    private int functionGrant(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        GrantBusinessFunctionForm form = new GrantBusinessFunctionForm();
        form.setFunctionId(requiredOption(args, "function-id", "function id"));
        form.setVersion(args.option("version"));
        form.setStatus(args.option("status"));
        ClientAppFunctionGrantDTO grant = businessAgentControlApi().grantFunctionToClientApp(clientAppId, form);
        out.println("function grant ok");
        printFunctionGrant("functionGrant", grant);
        return 0;
    }

    private int functionGrantStatus(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String grantId = requiredOption(args, "grant-id", "grant id");
        String status = requiredOption(args, "status", "status");
        ClientAppFunctionGrantDTO grant = businessAgentControlApi().updateFunctionGrantStatus(clientAppId, grantId, status);
        out.println("function grant-status ok");
        printFunctionGrant("functionGrant", grant);
        return 0;
    }

    private int functionVisible(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        List<BusinessFunctionSummaryDTO> functions = businessAgentControlApi()
                .listClientAppVisibleFunctionSummaries(clientAppId);
        out.println("functionVisibleCount=" + (functions != null ? functions.size() : 0));
        if (functions != null) {
            for (BusinessFunctionSummaryDTO function : functions) {
                printFunctionSummary(function);
            }
        }
        return 0;
    }

    private int routeList(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        List<ClientAppUpstreamRouteDTO> routes = businessAgentControlApi().listUpstreamRoutes(clientAppId);
        out.println("upstreamRouteCount=" + (routes != null ? routes.size() : 0));
        if (routes != null) {
            for (ClientAppUpstreamRouteDTO route : routes) {
                printUpstreamRoute("upstreamRoute", route);
            }
        }
        return 0;
    }

    private int routeSet(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String upstreamRef = requiredOption(args, "upstream-ref", "upstream ref");
        UpsertClientAppUpstreamRouteForm form = new UpsertClientAppUpstreamRouteForm();
        form.setBaseUrl(requiredOption(args, "url", "upstream base URL"));
        form.setUserTokenHeader(args.option("user-token-header"));
        form.setStatus(args.option("status"));
        form.setDescription(args.option("description"));
        ClientAppUpstreamRouteDTO route = businessAgentControlApi().upsertUpstreamRoute(clientAppId, upstreamRef, form);
        out.println("route set ok");
        printUpstreamRoute("upstreamRoute", route);
        return 0;
    }

    private int routeStatus(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String upstreamRef = requiredOption(args, "upstream-ref", "upstream ref");
        String status = requiredOption(args, "status", "status");
        ClientAppUpstreamRouteDTO route = businessAgentControlApi()
                .updateUpstreamRouteStatus(clientAppId, upstreamRef, status);
        out.println("route status ok");
        printUpstreamRoute("upstreamRoute", route);
        return 0;
    }

    private int modelGrants(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        List<ClientAppModelConfigGrantDTO> grants = businessAgentControlApi().listModelConfigGrants(clientAppId);
        out.println("modelGrantCount=" + (grants != null ? grants.size() : 0));
        if (grants != null) {
            for (ClientAppModelConfigGrantDTO grant : grants) {
                printModelConfigGrant("modelGrant", grant);
            }
        }
        return 0;
    }

    private int modelGrant(CliArguments args) {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        GrantModelConfigForm form = new GrantModelConfigForm();
        form.setModelConfigId(modelConfigId);
        form.setIsDefault(args.flag("set-default") || args.flag("default"));
        form.setGrantScope(args.option("grant-scope"));

        ClientAppModelConfigGrantDTO grant = businessAgentControlApi().grantModelConfig(clientAppId, form);
        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_MODEL_CONFIG_ID", valueOrEmpty(grant != null && hasText(grant.getModelConfigId())
                    ? grant.getModelConfigId()
                    : modelConfigId));
        }
        out.println("model grant ok");
        printModelConfigGrant("modelGrant", grant);
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_MODEL_CONFIG_ID");
        }
        return 0;
    }

    private int modelSetDefault(CliArguments args) {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        Long grantId = resolveModelGrantId(args, clientAppId);
        ClientAppModelConfigGrantDTO grant = businessAgentControlApi().setDefaultModelConfigGrant(clientAppId, grantId);
        if (args.flag("write-profile")) {
            String modelConfigId = grant != null && hasText(grant.getModelConfigId())
                    ? grant.getModelConfigId()
                    : args.option("model-config-id");
            if (!hasText(modelConfigId)) {
                throw new UpstreamCliException("model set-default response did not include modelConfigId; use --model-config-id with --write-profile");
            }
            config.writeProfileValue("NAVI_MODEL_CONFIG_ID", modelConfigId);
        }
        out.println("model set-default ok");
        printModelConfigGrant("modelGrant", grant);
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_MODEL_CONFIG_ID");
        }
        return 0;
    }

    private Long resolveModelGrantId(CliArguments args, String clientAppId) {
        String grantId = args.option("grant-id");
        if (hasText(grantId)) {
            return parseLong(grantId, "grant id");
        }
        String modelConfigId = requiredOption(args, "model-config-id", "model config id or grant id");
        List<ClientAppModelConfigGrantDTO> grants = businessAgentControlApi().listModelConfigGrants(clientAppId);
        if (grants != null) {
            for (ClientAppModelConfigGrantDTO grant : grants) {
                if (modelConfigId.equals(grant.getModelConfigId())) {
                    return grant.getId();
                }
            }
        }
        throw new UpstreamCliException("model config grant not found for modelConfigId: " + modelConfigId);
    }

    private int modelCreate(CliArguments args) {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        ClientAppModelConfigForm form = buildModelConfigForm(args, true);
        form.setSetDefault(args.flag("set-default") || args.flag("default"));

        ClientAppModelConfigGrantDTO grant = businessAgentControlApi().createClientAppModelConfig(clientAppId, form);
        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_MODEL_CONFIG_ID", modelConfigIdFromGrant(grant));
        }
        out.println("model create ok");
        printModelConfigGrant("modelGrant", grant);
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_MODEL_CONFIG_ID");
        }
        return 0;
    }

    private int modelUpdate(CliArguments args) {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        ClientAppModelConfigForm form = buildModelConfigForm(args, false);
        form.setSetDefault(args.flag("set-default") || args.flag("default"));

        ClientAppModelConfigGrantDTO grant = businessAgentControlApi()
                .updateClientAppModelConfig(clientAppId, modelConfigId, form);
        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_MODEL_CONFIG_ID", modelConfigIdFromGrantOrFallback(grant, modelConfigId));
        }
        out.println("model update ok");
        printModelConfigGrant("modelGrant", grant);
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_MODEL_CONFIG_ID");
        }
        return 0;
    }

    private int safeAsk(CliArguments args) {
        String agent = agentCode(args);
        String upstreamUserId = upstreamUserId(args);
        String message = requiredOption(args, "message", "message");
        String maxTurns = args.option("max-turns");
        if (hasText(maxTurns) && !"1".equals(maxTurns.trim())) {
            throw new UpstreamCliException("SAFE_SMOKE_MAX_TURNS_MUST_BE_ONE");
        }
        rejectSafeAskScopeOverride(args, "allowed-tools");
        rejectSafeAskScopeOverride(args, "allowed-functions");
        String clientRequestId = beginRuntimeClientRequest("safe-ask", agent, upstreamUserId);
        AgentTask task;
        try {
            task = agentApi().safeSmokeWithClientAppAccessToken(
                    agent,
                    message,
                    args.option("context-id"),
                    modelConfigId(args),
                    modelVariant(args),
                    clientAppKey(args),
                    clientAppAccessToken(args),
                    upstreamUserId,
                    clientRequestId);
        } catch (RuntimeRequestFailure e) {
            throw e;
        } catch (NavigatorApiException e) {
            throw runtimeRequestFailure(e, "SAFE_ASK_RESPONSE_NOT_RECEIVED", clientRequestId);
        } catch (RuntimeException e) {
            throw new RuntimeRequestFailure(
                    "sanitizedErrorCode=SAFE_ASK_CLIENT_FAILURE clientRequestId=" + clientRequestId);
        }
        if (task == null) {
            throw new RuntimeRequestFailure(
                    "sanitizedErrorCode=SAFE_ASK_EMPTY_RESPONSE clientRequestId=" + clientRequestId);
        }
        printTask(task);
        return 0;
    }

    private void rejectSafeAskScopeOverride(CliArguments args, String option) {
        String value = args.option(option);
        if (value != null && !value.isBlank() && !"none".equalsIgnoreCase(value.trim())) {
            throw new UpstreamCliException("--" + option + " must be none for runtime safe-ask");
        }
    }

    // ===== Explicit system-admin ClientApp scope =====

    private String systemAdminScopedClientAppId(CliArguments args) {
        if (hasText(args.option("target-tenant-id"))) {
            throw new UpstreamCliException("platform app-scope derives tenant from --client-app-id and does not accept --target-tenant-id");
        }
        String clientAppId = args.option("client-app-id");
        if (!hasText(clientAppId)) {
            throw new UpstreamCliException("platform app-scope requires explicit --client-app-id; NAVI_CLIENT_APP_ID is not used");
        }
        return clientAppId.trim();
    }

    private String beginSystemAdminClientAppScope(CliArguments args) {
        String clientAppId = systemAdminScopedClientAppId(args);
        printSystemAdminClientAppScope(upstreamAdminApi().inspectUpstreamAdminClientAppScope(clientAppId));
        return clientAppId;
    }

    private int platformAppScopeInspect(CliArguments args) {
        beginSystemAdminClientAppScope(args);
        return 0;
    }

    private int platformAppScopeAgentList(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        List<BusinessAgentBundleDTO> agents = upstreamAdminApi().listUpstreamAdminClientAppAgents(clientAppId);
        out.println("agentCount=" + (agents == null ? 0 : agents.size()));
        printJson(agents);
        return 0;
    }

    private int platformAppScopeAgentGet(CliArguments args) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printBusinessAgentBundle(upstreamAdminApi().getUpstreamAdminClientAppAgent(clientAppId, agentCode(args)));
        return 0;
    }

    private int platformAppScopeAgentSync(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        SyncBusinessAgentBundleForm form = readJsonFile(requiredOption(args, "manifest", "manifest path"), SyncBusinessAgentBundleForm.class);
        if (hasText(form.getClientAppId()) && !clientAppId.equals(form.getClientAppId().trim())) {
            throw new UpstreamCliException("agent manifest clientAppId does not match explicit --client-app-id");
        }
        form.setClientAppId(clientAppId);
        printBusinessAgentBundle(upstreamAdminApi().syncUpstreamAdminClientAppAgent(clientAppId, form));
        return 0;
    }

    private int platformAppScopeModelGrants(CliArguments args) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        List<ClientAppModelConfigGrantDTO> grants = upstreamAdminApi().listUpstreamAdminClientAppModelConfigGrants(clientAppId);
        out.println("modelGrantCount=" + (grants == null ? 0 : grants.size()));
        if (grants != null) grants.forEach(grant -> printModelConfigGrant("modelGrant", grant));
        return 0;
    }

    private int platformAppScopeModelGrant(CliArguments args) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        GrantModelConfigForm form = new GrantModelConfigForm();
        form.setModelConfigId(requiredOption(args, "model-config-id", "model config id"));
        form.setIsDefault(args.flag("set-default") || args.flag("default"));
        form.setGrantScope(args.option("grant-scope"));
        printModelConfigGrant("modelGrant", upstreamAdminApi().grantUpstreamAdminClientAppModelConfig(clientAppId, form));
        return 0;
    }

    private int platformAppScopeModelSetDefault(CliArguments args) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        Long grantId = resolveSystemAdminScopeModelGrantId(args, clientAppId);
        printModelConfigGrant("modelGrant", upstreamAdminApi().setDefaultUpstreamAdminClientAppModelConfigGrant(clientAppId, grantId));
        return 0;
    }

    private Long resolveSystemAdminScopeModelGrantId(CliArguments args, String clientAppId) {
        if (hasText(args.option("grant-id"))) return parseLong(args.option("grant-id"), "grant id");
        String modelConfigId = requiredOption(args, "model-config-id", "model config id or grant id");
        List<ClientAppModelConfigGrantDTO> grants = upstreamAdminApi().listUpstreamAdminClientAppModelConfigGrants(clientAppId);
        if (grants != null) for (ClientAppModelConfigGrantDTO grant : grants) {
            if (modelConfigId.equals(grant.getModelConfigId())) return grant.getId();
        }
        throw new UpstreamCliException("model config grant not found for modelConfigId: " + modelConfigId);
    }

    private int platformAppScopeModelCreate(CliArguments args) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        ClientAppModelConfigForm form = buildModelConfigForm(args, true);
        form.setSetDefault(args.flag("set-default") || args.flag("default"));
        printModelConfigGrant("modelGrant", upstreamAdminApi().createUpstreamAdminClientAppModelConfig(clientAppId, form));
        return 0;
    }

    private int platformAppScopeModelGet(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminApi().getUpstreamAdminClientAppModelConfig(
                clientAppId, requiredOption(args, "model-config-id", "model config id")));
        return 0;
    }

    private int platformAppScopeModelUpdate(CliArguments args) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        ClientAppModelConfigForm form = buildModelConfigForm(args, false);
        form.setSetDefault(args.flag("set-default") || args.flag("default"));
        printModelConfigGrant("modelGrant", upstreamAdminApi().updateUpstreamAdminClientAppModelConfig(clientAppId, modelConfigId, form));
        return 0;
    }

    private int platformAppScopeModelRotateKey(CliArguments args, boolean clear) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        RotateModelConfigKeyForm form = new RotateModelConfigKeyForm();
        if (clear) form.setClearApiKey(true);
        else form.setApiKey(config.required("NAVI_LLM_API_KEY", "LLM API key; pass --api-key-env <envName>"));
        printModelConfigGrant("modelGrant", upstreamAdminApi().rotateUpstreamAdminClientAppModelConfigKey(
                clientAppId, requiredOption(args, "model-config-id", "model config id"), form));
        return 0;
    }

    private int platformAppScopeUserGrants(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        List<ClientAppUpstreamUserGrantDTO> grants = upstreamAdminApi().listUpstreamAdminClientAppUpstreamUsers(clientAppId);
        out.println("upstreamUserGrantCount=" + (grants == null ? 0 : grants.size()));
        printJson(grants);
        return 0;
    }

    private int platformAppScopeUserGrant(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        GrantUpstreamUserForm form = new GrantUpstreamUserForm();
        form.setUpstreamUserId(requiredOption(args, "upstream-user-id", "upstream user id"));
        form.setUpstreamUserToken(args.option("upstream-user-token"));
        form.setStatus(hasText(args.option("status")) ? args.option("status") : "ENABLED");
        printJson(upstreamAdminApi().grantUpstreamAdminClientAppUpstreamUser(clientAppId, form));
        return 0;
    }

    private int platformAppScopeUserStatus(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminApi().updateUpstreamAdminClientAppUpstreamUserStatus(clientAppId,
                requiredOption(args, "upstream-user-id", "upstream user id"), requiredOption(args, "status", "status")));
        return 0;
    }

    private int platformAppScopeModelBindings(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminApi().listUpstreamAdminClientAppAgentModelBindings(clientAppId, agentCode(args)));
        return 0;
    }
    private int platformAppScopeBindModel(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminApi().bindUpstreamAdminClientAppAgentModel(clientAppId, agentCode(args), bindAgentModelForm(args)));
        return 0;
    }
    private int platformAppScopeUnbindModel(CliArguments args) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        upstreamAdminApi().unbindUpstreamAdminClientAppAgentModel(clientAppId, agentCode(args), requiredOption(args, "model-config-id", "model config id"));
        out.println("agent unbind-model ok"); return 0;
    }
    private int platformAppScopeSetDefaultModel(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminApi().setDefaultUpstreamAdminClientAppAgentModel(clientAppId, agentCode(args), bindAgentModelForm(args)));
        return 0;
    }
    private int platformAppScopeWorkspaceBindings(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminApi().listUpstreamAdminClientAppAgentWorkspaceBindings(clientAppId, agentCode(args)));
        return 0;
    }
    private int platformAppScopeBindWorkspace(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminApi().bindUpstreamAdminClientAppAgentWorkspace(clientAppId, agentCode(args), bindAgentWorkspaceForm(args)));
        return 0;
    }
    private int platformAppScopeUnbindWorkspace(CliArguments args) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        upstreamAdminApi().unbindUpstreamAdminClientAppAgentWorkspace(clientAppId, agentCode(args), requiredOption(args, "directory-id", "directory id"));
        out.println("agent unbind-workspace ok"); return 0;
    }
    private int platformAppScopeSetDefaultWorkspace(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminApi().setDefaultUpstreamAdminClientAppAgentWorkspace(clientAppId, agentCode(args), bindAgentWorkspaceForm(args)));
        return 0;
    }
    private int platformAppScopeWorkerBindings(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminApi().listUpstreamAdminClientAppAgentWorkerBindings(clientAppId, agentCode(args)));
        return 0;
    }
    private int platformAppScopeBindWorker(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminApi().bindUpstreamAdminClientAppAgentWorker(clientAppId, agentCode(args), bindAgentWorkerForm(args)));
        return 0;
    }
    private int platformAppScopeUnbindWorker(CliArguments args) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        upstreamAdminApi().unbindUpstreamAdminClientAppAgentWorker(clientAppId, agentCode(args), requiredOption(args, "worker-pool-id", "worker pool id"));
        out.println("agent unbind-worker ok"); return 0;
    }
    private int platformAppScopeSetDefaultWorker(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminApi().setDefaultUpstreamAdminClientAppAgentWorker(clientAppId, agentCode(args), bindAgentWorkerForm(args)));
        return 0;
    }
    private int platformAppScopeDirectoryList(CliArguments args) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        List<Directory> dirs = upstreamAdminDirectoryApi().listWithUpstreamAdminClientAppScope(clientAppId,
                args.option("worker-id"), args.option("workspace-scope"), args.option("upstream-user-id"));
        out.println("directoryCount=" + (dirs == null ? 0 : dirs.size()));
        if (dirs != null) dirs.forEach(this::printDirectory);
        return 0;
    }
    private int platformAppScopeDirectoryInit(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printDirectory(upstreamAdminDirectoryApi().initWithUpstreamAdminClientAppScope(clientAppId,
                readJsonMap(requiredOption(args, "file", "directory init json file"))));
        return 0;
    }
    private int platformAppScopeDirectoryGet(CliArguments args) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printDirectory(upstreamAdminDirectoryApi().getWithUpstreamAdminClientAppScope(clientAppId,
                requiredOption(args, "directory-id", "directory id")));
        return 0;
    }
    private int platformAppScopeDirectoryDelete(CliArguments args) {
        String clientAppId = beginSystemAdminClientAppScope(args);
        upstreamAdminDirectoryApi().deleteWithUpstreamAdminClientAppScope(clientAppId,
                requiredOption(args, "directory-id", "directory id"));
        out.println("directory delete ok"); return 0;
    }
    private int platformAppScopeDirectoryEnv(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminDirectoryApi().updateEnvVarsWithUpstreamAdminClientAppScope(clientAppId,
                requiredOption(args, "directory-id", "directory id"),
                readJsonStringMap(requiredOption(args, "file", "env json file"))));
        return 0;
    }
    private int platformAppScopeDirectoryFiles(CliArguments args) throws Exception {
        String clientAppId = beginSystemAdminClientAppScope(args);
        printJson(upstreamAdminDirectoryApi().updateFilesWithUpstreamAdminClientAppScope(clientAppId,
                requiredOption(args, "directory-id", "directory id"),
                readJsonStringMap(requiredOption(args, "file", "files json file"))));
        return 0;
    }

    private int modelTest(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String reply = businessAgentControlApi().testClientAppModelConfig(
                clientAppId, buildModelConnectionTestForm(args));
        out.println("model test ok");
        out.println("reply=" + valueOrEmpty(reply));
        return 0;
    }

    private int modelTestSaved(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        String reply = businessAgentControlApi().testSavedClientAppModelConfig(
                clientAppId, modelConfigId, args.option("worker-id"));
        out.println("model test-saved ok");
        out.println("reply=" + valueOrEmpty(reply));
        return 0;
    }

    private int modelRotateKey(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        RotateModelConfigKeyForm form = new RotateModelConfigKeyForm();
        form.setApiKey(config.required("NAVI_LLM_API_KEY", "LLM API key; pass --api-key-env <envName>"));

        ClientAppModelConfigGrantDTO grant = businessAgentControlApi()
                .rotateClientAppModelConfigKey(clientAppId, modelConfigId, form);
        out.println("model rotate-key ok");
        printModelConfigGrant("modelGrant", grant);
        return 0;
    }

    private int modelClearKey(CliArguments args) {
        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        RotateModelConfigKeyForm form = new RotateModelConfigKeyForm();
        form.setClearApiKey(true);

        ClientAppModelConfigGrantDTO grant = businessAgentControlApi()
                .rotateClientAppModelConfigKey(clientAppId, modelConfigId, form);
        out.println("model clear-key ok");
        printModelConfigGrant("modelGrant", grant);
        return 0;
    }

    private int modelSystemList(CliArguments args) {
        List<LlmModelConfigDTO> models = upstreamAdminApi()
                .listUpstreamSystemModelConfigs(args.option("target-tenant-id"));
        out.println("modelConfigCount=" + (models != null ? models.size() : 0));
        if (models != null) {
            for (LlmModelConfigDTO model : models) {
                printLlmModelConfig("modelConfig", model);
            }
        }
        return 0;
    }

    private int modelSystemCreate(CliArguments args) {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        ClientAppModelConfigForm form = buildModelConfigForm(args, true);
        LlmModelConfigDTO model = upstreamAdminApi()
                .createUpstreamSystemModelConfig(form, args.option("target-tenant-id"));
        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_MODEL_CONFIG_ID", modelConfigIdFromModel(model));
        }
        out.println("model system-create ok");
        printLlmModelConfig("modelConfig", model);
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_MODEL_CONFIG_ID");
        }
        return 0;
    }

    private int modelSystemUpdate(CliArguments args) {
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        ClientAppModelConfigForm form = buildModelConfigForm(args, false);
        LlmModelConfigDTO model = upstreamAdminApi()
                .updateUpstreamSystemModelConfig(modelConfigId, form, args.option("target-tenant-id"));
        if (args.flag("write-profile")) {
            config.writeProfileValue("NAVI_MODEL_CONFIG_ID", modelConfigIdFromModelOrFallback(model, modelConfigId));
        }
        out.println("model system-update ok");
        printLlmModelConfig("modelConfig", model);
        if (args.flag("write-profile")) {
            out.println("profileUpdated=" + config.profilePath());
            out.println("stored=NAVI_MODEL_CONFIG_ID");
        }
        return 0;
    }

    private int modelSystemGet(CliArguments args) {
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        LlmModelConfigDTO model = upstreamAdminApi()
                .getUpstreamSystemModelConfig(modelConfigId, args.option("target-tenant-id"));
        printLlmModelConfig("modelConfig", model);
        return 0;
    }

    private int modelSystemTest(CliArguments args) {
        String reply = upstreamAdminApi().testUpstreamSystemModelConfig(
                buildModelConnectionTestForm(args), args.option("target-tenant-id"));
        out.println("model system-test ok");
        out.println("reply=" + valueOrEmpty(reply));
        return 0;
    }

    private int modelSystemTestSaved(CliArguments args) {
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        String reply = upstreamAdminApi().testSavedUpstreamSystemModelConfig(
                modelConfigId, args.option("worker-id"), args.option("target-tenant-id"));
        out.println("model system-test-saved ok");
        out.println("reply=" + valueOrEmpty(reply));
        return 0;
    }

    private int modelSystemRotateKey(CliArguments args) {
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        RotateModelConfigKeyForm form = new RotateModelConfigKeyForm();
        form.setApiKey(config.required("NAVI_LLM_API_KEY", "LLM API key; pass --api-key-env <envName>"));

        LlmModelConfigDTO model = upstreamAdminApi()
                .rotateUpstreamSystemModelConfigKey(modelConfigId, form, args.option("target-tenant-id"));
        out.println("model system-rotate-key ok");
        printLlmModelConfig("modelConfig", model);
        return 0;
    }

    private int modelSystemClearKey(CliArguments args) {
        String modelConfigId = requiredOption(args, "model-config-id", "model config id");
        RotateModelConfigKeyForm form = new RotateModelConfigKeyForm();
        form.setClearApiKey(true);

        LlmModelConfigDTO model = upstreamAdminApi()
                .rotateUpstreamSystemModelConfigKey(modelConfigId, form, args.option("target-tenant-id"));
        out.println("model system-clear-key ok");
        printLlmModelConfig("modelConfig", model);
        return 0;
    }

    private ClientAppModelConfigForm buildModelConfigForm(CliArguments args, boolean create) {
        ClientAppModelConfigForm form = new ClientAppModelConfigForm();
        String workerBackend = args.option("worker-backend");
        boolean subscriptionBackend = isSubscriptionWorkerBackend(workerBackend);
        form.setName(create ? requiredOption(args, "name", "model name") : args.option("name"));
        form.setBaseUrl(create && !subscriptionBackend
                ? requiredOption(args, "model-base-url", "LLM model base URL")
                : args.option("model-base-url"));
        form.setModelName(create ? requiredOption(args, "model-name", "LLM model name") : args.option("model-name"));
        form.setCategory(args.option("category"));
        String provider = args.option("provider");
        if (hasText(provider)) {
            form.setEnvVars(Map.of("NAVI_LLM_PROVIDER", provider));
        }
        String availableModels = args.option("available-models");
        if (hasText(availableModels)) {
            form.setAvailableModels(parseCsv(availableModels));
        }
        form.setRuntimeBudgetPresetKey(args.option("runtime-budget-preset"));
        form.setRuntimeBudgetOverrideJson(args.option("runtime-budget-override-json"));
        form.setWorkerBackend(workerBackend);
        if (create && !subscriptionBackend) {
            form.setApiKey(config.required("NAVI_LLM_API_KEY", "LLM API key; pass --api-key-env <envName>"));
        }
        return form;
    }

    private ClientAppModelConfigForm buildModelConnectionTestForm(CliArguments args) {
        ClientAppModelConfigForm form = new ClientAppModelConfigForm();
        String workerBackend = requiredOption(args, "worker-backend", "worker backend");
        form.setWorkerBackend(workerBackend);
        form.setWorkerId(args.option("worker-id"));
        form.setModelName(requiredOption(args, "model-name", "LLM model name"));
        form.setBaseUrl(args.option("model-base-url"));
        String availableModels = args.option("available-models");
        if (hasText(availableModels)) {
            form.setAvailableModels(parseCsv(availableModels));
        }
        if (!isSubscriptionWorkerBackend(workerBackend)) {
            form.setBaseUrl(requiredOption(args, "model-base-url", "LLM model base URL"));
            form.setApiKey(config.required("NAVI_LLM_API_KEY", "LLM API key; pass --api-key-env <envName>"));
        }
        return form;
    }

    private boolean isSubscriptionWorkerBackend(String workerBackend) {
        if (!hasText(workerBackend)) {
            return false;
        }
        String normalized = workerBackend.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        return "OPENAI_CODEX".equals(normalized)
                || "OPENAI_CODEX_APP_SERVER".equals(normalized);
    }

    private void printLlmModelConfig(String prefix, LlmModelConfigDTO model) {
        if (model == null) {
            return;
        }
        out.println(prefix + ".id=" + valueOrEmpty(model.getId()));
        out.println(prefix + ".tenantId=" + valueOrEmpty(model.getTenantId()));
        out.println(prefix + ".name=" + valueOrEmpty(model.getName()));
        out.println(prefix + ".category=" + valueOrEmpty(model.getCategory()));
        out.println(prefix + ".baseUrl=" + valueOrEmpty(model.getBaseUrl()));
        out.println(prefix + ".modelName=" + valueOrEmpty(model.getModelName()));
        out.println(prefix + ".isDefault=" + valueOrEmpty(model.getIsDefault()));
        out.println(prefix + ".hasApiKey=" + valueOrEmpty(model.getHasApiKey()));
        out.println(prefix + ".scope=" + valueOrEmpty(model.getScope()));
        out.println(prefix + ".allowedWorkerIds=" + joinValues(model.getAllowedWorkerIds()));
        out.println(prefix + ".workerBackend=" + valueOrEmpty(model.getWorkerBackend()));
        out.println(prefix + ".availableModels=" + joinValues(model.getAvailableModels()));
        out.println(prefix + ".runtimeBudgetPresetKey=" + valueOrEmpty(model.getRuntimeBudgetPresetKey()));
        out.println(prefix + ".runtimeBudgetOverrideJson=" + valueOrEmpty(model.getRuntimeBudgetOverrideJson()));
        out.println(prefix + ".ownerType=" + valueOrEmpty(model.getOwnerType()));
        out.println(prefix + ".ownerId=" + valueOrEmpty(model.getOwnerId()));
        out.println(prefix + ".enabled=" + valueOrEmpty(model.getEnabled()));
        out.println(prefix + ".sortOrder=" + valueOrEmpty(model.getSortOrder()));
        out.println(prefix + ".createdAt=" + valueOrEmpty(model.getCreatedAt()));
        out.println(prefix + ".updatedAt=" + valueOrEmpty(model.getUpdatedAt()));
    }

    private static String joinValues(List<String> values) {
        return values == null || values.isEmpty() ? "(empty)" : String.join(",", values);
    }

    private String modelConfigIdFromGrant(ClientAppModelConfigGrantDTO grant) {
        String modelConfigId = grant != null ? grant.getModelConfigId() : null;
        if (!hasText(modelConfigId)) {
            throw new UpstreamCliException("model create response did not include modelConfigId");
        }
        return modelConfigId;
    }

    private String modelConfigIdFromGrantOrFallback(ClientAppModelConfigGrantDTO grant, String fallback) {
        String modelConfigId = grant != null ? grant.getModelConfigId() : null;
        return hasText(modelConfigId) ? modelConfigId : fallback;
    }

    private String modelConfigIdFromModel(LlmModelConfigDTO model) {
        String modelConfigId = model != null ? model.getId() : null;
        if (!hasText(modelConfigId)) {
            throw new UpstreamCliException("model config response did not include id");
        }
        return modelConfigId;
    }

    private String modelConfigIdFromModelOrFallback(LlmModelConfigDTO model, String fallback) {
        String modelConfigId = model != null ? model.getId() : null;
        return hasText(modelConfigId) ? modelConfigId : fallback;
    }

    private List<String> parseCsv(String value) {
        if (!hasText(value)) {
            return null;
        }
        List<String> values = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(UpstreamCli::hasText)
                .toList();
        return values.isEmpty() ? null : values;
    }

    private void putOptionalCsv(Map<String, Object> target,
                                CliArguments args,
                                String payloadKey,
                                String option,
                                String configKey) {
        boolean optionProvided = args.options().containsKey(option);
        String raw = optionProvided ? args.option(option) : config.get(configKey);
        if (optionProvided && (!hasText(raw) || "none".equalsIgnoreCase(raw.trim()))) {
            target.put(payloadKey, List.of());
            return;
        }
        if (!optionProvided && hasText(raw) && "none".equalsIgnoreCase(raw.trim())) {
            target.put(payloadKey, List.of());
            return;
        }
        List<String> values = parseCsv(raw);
        if (values != null) {
            target.put(payloadKey, values);
        }
    }

    private int accountContextList(CliArguments args) {
        String upstreamUserId = upstreamUserId(args);
        AccountContextFileTreeDTO tree = agentApi().listAccountContextFilesWithClientAppAccessToken(
                clientAppKey(args),
                clientAppAccessToken(args),
                upstreamUserId);
        out.println("accountId=" + valueOrEmpty(tree != null ? tree.getAccountId() : null));
        if (tree != null && tree.getFiles() != null) {
            for (AccountContextFileDTO file : tree.getFiles()) {
                printAccountContextFileMetadata(file);
            }
        }
        return 0;
    }

    private int accountContextRead(CliArguments args) {
        String upstreamUserId = upstreamUserId(args);
        String fileName = requiredOption(args, "file", "account context file");
        AccountContextFileDTO file = agentApi().readAccountContextFileWithClientAppAccessToken(
                fileName,
                clientAppKey(args),
                clientAppAccessToken(args),
                upstreamUserId);
        printAccountContextFileMetadata(file);
        out.println("content:");
        out.print(redact(valueOrEmpty(file != null ? file.getContent() : null)));
        if (file == null || file.getContent() == null || !file.getContent().endsWith("\n")) {
            out.println();
        }
        return 0;
    }

    private int accountContextWritePolicy(CliArguments args) throws Exception {
        String upstreamUserId = upstreamUserId(args);
        String source = requiredOption(args, "from", "source file");
        Path sourcePath = cwd.resolve(source).normalize();
        if (!Files.isRegularFile(sourcePath)) {
            throw new UpstreamCliException("source file not found: " + sourcePath);
        }

        AccountContextFileWriteForm form = new AccountContextFileWriteForm();
        form.setContent(Files.readString(sourcePath, StandardCharsets.UTF_8));
        form.setExpectedSha256(args.option("expected-sha256"));
        AccountContextFileDTO file = agentApi().writeAccountPolicyWithClientAppAccessToken(
                form,
                clientAppKey(args),
                clientAppAccessToken(args),
                upstreamUserId);
        out.println("account-context write-policy ok");
        printAccountContextFileMetadata(file);
        return 0;
    }

    private int unsupportedTmsHelper() {
        throw new UpstreamCliException("TMS test-only helper is not implemented in this CLI build; use env/profile tokens without printing secrets");
    }

    private ManagementAuthApi typedManagementAuthApi() {
        String principalCredential = resolveTypedManagementPrincipalCredential();
        HttpHelper typedManagementHttp = new HttpHelper(
                config.required("NAVI_BASE_URL", "Navigator base URL"),
                null,
                null,
                null,
                null,
                null,
                null,
                Duration.ofSeconds(30));
        return new ManagementAuthApi(typedManagementHttp, principalCredential);
    }

    private String resolveTypedManagementPrincipalCredential() {
        UpstreamCliConfig.TypedCredentialSource source = config.typedCredentialSource();
        if (source == UpstreamCliConfig.TypedCredentialSource.AMBIGUOUS) {
            throw new UpstreamCliException("typed-management credential source is ambiguous "
                    + "(TYPED_MANAGEMENT_CREDENTIAL_SOURCE_AMBIGUOUS)");
        }
        if (source == UpstreamCliConfig.TypedCredentialSource.EXPLICIT_ENV_MISSING) {
            throw new UpstreamCliException("typed-management explicit credential source is unavailable "
                    + "(TYPED_MANAGEMENT_CREDENTIAL_SOURCE_MISSING)");
        }
        if (source == UpstreamCliConfig.TypedCredentialSource.MISSING) {
            if (config.hasLegacyCredentialSource()) {
                throw new UpstreamCliException("legacy credential cannot be used for typed-management introspection "
                        + "(TYPED_MANAGEMENT_LEGACY_CREDENTIAL_ONLY)");
            }
            throw new UpstreamCliException("typed-management principal credential is required "
                    + "(TYPED_MANAGEMENT_CREDENTIAL_MISSING)");
        }
        if (config.hasLegacyCredentialSource()) {
            throw new UpstreamCliException("typed-management credential cannot be mixed with a legacy credential lane "
                    + "(TYPED_MANAGEMENT_LEGACY_CREDENTIAL_CONFLICT)");
        }
        String principalCredential = config.principalCredential();
        if (!hasText(principalCredential)) {
            throw new UpstreamCliException("typed-management principal credential is required "
                    + "(TYPED_MANAGEMENT_CREDENTIAL_MISSING)");
        }
        return principalCredential;
    }

    private void printTypedManagementIdentity(String command, Map<String, Object> response) {
        if (response == null || response.isEmpty()) {
            throw new UpstreamCliException("typed-management " + command + " response is incomplete "
                    + "(TYPED_MANAGEMENT_RESPONSE_INCOMPLETE)");
        }
        out.println("typedManagement=" + command);
        // P1B-A intentionally does not expose these fields. Do not infer or compute either locally.
        out.println("schemaVersion=" + NOT_SUPPLIED_BY_SERVER);
        out.println("principalType=" + safeTypedResponseValue(response, "principalType"));
        out.println("principalId=" + safeTypedResponseValue(response, "principalId"));
        out.println("sourceUpstreamSystemId=" + safeTypedResponseValue(response, "sourceUpstreamSystemId"));
        out.println("navigatorInstanceId=" + safeTypedResponseValue(response, "navigatorInstanceId"));
        out.println("environmentProfile=" + safeTypedResponseValue(response, "environmentProfile"));
        out.println("credentialLane=" + safeTypedResponseValue(response, "credentialLane"));
        out.println("credentialStatus=" + safeTypedResponseValue(response, "credentialStatus"));
        out.println("credentialExpiresAt=" + safeTypedResponseValue(response, "credentialExpiresAt"));
        out.println("credentialFingerprint=" + NOT_SUPPLIED_BY_SERVER);
        out.println("authorityCeilingActions=" + safeTypedActionSet(response, "authorityCeilingActions"));
        out.println("effectiveCredentialActions=" + safeTypedActionSet(response, "effectiveCredentialActions"));
    }

    private String safeTypedResponseValue(Map<String, Object> response, String field) {
        Object value = response.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            return NOT_SUPPLIED_BY_SERVER;
        }
        return redact(String.valueOf(value));
    }

    private String safeTypedActionSet(Map<String, Object> response, String field) {
        Object value = response.get(field);
        if (!(value instanceof Collection<?> actions)) {
            return NOT_SUPPLIED_BY_SERVER;
        }
        List<String> safeActions = actions.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(UpstreamCli::hasText)
                .sorted()
                .map(this::redact)
                .toList();
        return safeActions.isEmpty() ? "(none)" : String.join(",", safeActions);
    }

    private static void validateManagementReference(String value) {
        if (!hasText(value) || value.length() > 512 || !value.equals(value.trim())
                || !MANAGEMENT_REFERENCE_PATTERN.matcher(value).matches()) {
            throw new UpstreamCliException("typed-management explain reference is invalid "
                    + "(TYPED_MANAGEMENT_EXPLAIN_REFERENCE_INVALID)");
        }
    }

    private AgentApi agentApi() {
        return new AgentApi(openHttp());
    }

    private BusinessAgentApi bootstrapApi() {
        return new BusinessAgentApi(openHttp());
    }

    private BusinessAgentApi operatorOrAdminApi() {
        String operatorApiKey = config.get("NAVI_OPERATOR_API_KEY");
        String adminToken = config.get("NAVI_ADMIN_TOKEN");
        if (!hasText(operatorApiKey) && !hasText(adminToken)) {
            throw new UpstreamCliException("operator/admin credential is required (NAVI_OPERATOR_API_KEY or NAVI_ADMIN_TOKEN)");
        }
        return new BusinessAgentApi(new HttpHelper(
                config.required("NAVI_BASE_URL", "Navigator base URL"),
                null,
                adminToken,
                config.get("NAVI_TENANT_ID"),
                null,
                operatorApiKey,
                Duration.ofSeconds(30)));
    }

    private BusinessAgentApi upstreamAdminApi() {
        requireLegacyPlatformLane();
        String adminApiKey = config.get("NAVI_ADMIN_API_KEY");
        return new BusinessAgentApi(new HttpHelper(
                config.required("NAVI_BASE_URL", "Navigator base URL"),
                null,
                null,
                config.get("NAVI_TENANT_ID"),
                null,
                null,
                adminApiKey,
                Duration.ofSeconds(30)));
    }

    private WorkerApi upstreamAdminWorkerApi() {
        return new WorkerApi(upstreamAdminHttp());
    }

    private DirectoryApi upstreamAdminDirectoryApi() {
        return new DirectoryApi(upstreamAdminHttp());
    }

    private DirectoryApi clientAppDirectoryApi() {
        requireClientAppControlLane();
        String controlApiKey = config.get("NAVI_CONTROL_API_KEY");
        return new DirectoryApi(new HttpHelper(
                config.required("NAVI_BASE_URL", "Navigator base URL"),
                null,
                null,
                config.get("NAVI_TENANT_ID"),
                controlApiKey,
                null,
                null,
                Duration.ofSeconds(30)));
    }

    private HttpHelper upstreamAdminHttp() {
        requireLegacyPlatformLane();
        String adminApiKey = config.get("NAVI_ADMIN_API_KEY");
        return new HttpHelper(
                config.required("NAVI_BASE_URL", "Navigator base URL"),
                null,
                null,
                config.get("NAVI_TENANT_ID"),
                null,
                null,
                adminApiKey,
                Duration.ofSeconds(30));
    }

    private BusinessAgentApi businessAgentControlApi() {
        requireClientAppControlLane();
        String controlApiKey = config.get("NAVI_CONTROL_API_KEY");
        return new BusinessAgentApi(new HttpHelper(
                config.required("NAVI_BASE_URL", "Navigator base URL"),
                null,
                null,
                config.get("NAVI_TENANT_ID"),
                controlApiKey,
                null,
                null,
                Duration.ofSeconds(30)));
    }

    private HttpHelper openHttp() {
        return new HttpHelper(config.required("NAVI_BASE_URL", "Navigator base URL"),
                null, null, config.get("NAVI_TENANT_ID"), Duration.ofSeconds(30));
    }

    private HttpHelper runtimeAuditHttp() {
        return new HttpHelper(
                config.required("NAVI_BASE_URL", "Navigator base URL"),
                null,
                null,
                null,
                Duration.ofSeconds(30));
    }

    private void requireLegacyPlatformLane() {
        if (!hasText(config.get("NAVI_ADMIN_API_KEY"))) {
            throw new UpstreamCliException("upstream admin credential is required (NAVI_ADMIN_API_KEY)");
        }
        rejectForeignCredentialMaterial("platform legacy", "NAVI_CONTROL_API_KEY", "NAVIGATOR_CONTROL_API_KEY",
                "NAVI_ADMIN_TOKEN", "NAVIGATOR_ADMIN_TOKEN", "NAVI_OPERATOR_API_KEY", "NAVIGATOR_OPERATOR_API_KEY",
                "NAVI_CLIENT_APP_KEY", "CLIENT_APP_KEY", "NAVI_CLIENT_APP_SECRET", "CLIENT_APP_SECRET",
                "NAVI_CLIENT_APP_ACCESS_TOKEN", "NAVI_CLIENT_APP_RUNTIME_TOKEN", "CLIENT_APP_RUNTIME_TOKEN",
                "NAVI_PRINCIPAL_CREDENTIAL", "NAVI_USER_API_KEY", "NAVI_ADMIN_KEY_CLAIM_TOKEN",
                "NAVI_WORKER_CREDENTIAL", "NAVI_TASK_SCOPED_TOKEN");
    }

    private void requireClientAppControlLane() {
        if (!hasText(config.get("NAVI_CONTROL_API_KEY"))) {
            throw new UpstreamCliException("client app control credential is required (NAVI_CONTROL_API_KEY)");
        }
        rejectForeignCredentialMaterial("ClientApp control", "NAVI_ADMIN_API_KEY", "NAVIGATOR_ADMIN_API_KEY",
                "NAVI_ADMIN_TOKEN", "NAVIGATOR_ADMIN_TOKEN", "NAVI_OPERATOR_API_KEY", "NAVIGATOR_OPERATOR_API_KEY",
                "NAVI_CLIENT_APP_KEY", "CLIENT_APP_KEY", "NAVI_CLIENT_APP_SECRET", "CLIENT_APP_SECRET",
                "NAVI_CLIENT_APP_ACCESS_TOKEN", "NAVI_CLIENT_APP_RUNTIME_TOKEN", "CLIENT_APP_RUNTIME_TOKEN",
                "NAVI_PRINCIPAL_CREDENTIAL", "NAVI_USER_API_KEY", "NAVI_ADMIN_KEY_CLAIM_TOKEN",
                "NAVI_WORKER_CREDENTIAL", "NAVI_TASK_SCOPED_TOKEN");
    }

    private void requireRuntimeLane() {
        rejectForeignCredentialMaterial("runtime", "NAVI_ADMIN_API_KEY", "NAVIGATOR_ADMIN_API_KEY",
                "NAVI_ADMIN_TOKEN", "NAVIGATOR_ADMIN_TOKEN", "NAVI_OPERATOR_API_KEY", "NAVIGATOR_OPERATOR_API_KEY",
                "NAVI_CONTROL_API_KEY", "NAVIGATOR_CONTROL_API_KEY", "NAVI_PRINCIPAL_CREDENTIAL",
                "NAVI_USER_API_KEY", "NAVI_ADMIN_KEY_CLAIM_TOKEN", "NAVI_WORKER_CREDENTIAL", "NAVI_TASK_SCOPED_TOKEN");
    }

    private void rejectForeignCredentialMaterial(String lane, String... keys) {
        List<String> present = config.presentCredentialKeys(keys);
        if (!present.isEmpty()) {
            throw new UpstreamCliException(lane + " lane refuses mixed credential material: " + String.join(",", present));
        }
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
        ClientAppRuntimeAccessTokenDTO token = api.exchangeRuntimeAccessToken(
                appKey,
                appSecret,
                activeClientRequestId,
                activeRuntimeOperation,
                activeRuntimeAgentCode,
                activeRuntimeUpstreamUserId);
        if (token == null || !hasText(token.getAccessToken())) {
            if (hasText(activeClientRequestId)) {
                throw new RuntimeRequestFailure(
                        "sanitizedErrorCode=RUNTIME_TOKEN_EMPTY_RESPONSE clientRequestId="
                                + activeClientRequestId);
            }
            throw new UpstreamCliException("runtime token response did not include accessToken");
        }
        config.setValue("NAVI_CLIENT_APP_ACCESS_TOKEN", token.getAccessToken());
        return token;
    }

    private String beginRuntimeClientRequest(String operation, String agentCode, String upstreamUserId) {
        return beginRuntimeClientRequest(operation, agentCode, upstreamUserId, null);
    }

    private String beginRuntimeClientRequest(
            String operation,
            String agentCode,
            String upstreamUserId,
            String replayClientRequestId) {
        String clientRequestId = hasText(replayClientRequestId)
                ? replayClientRequestId.trim()
                : UUID.randomUUID().toString();
        if (!clientRequestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")) {
            throw new UpstreamCliException("replay client request id is invalid");
        }
        activeClientRequestId = clientRequestId;
        activeRuntimeOperation = operation;
        activeRuntimeAgentCode = agentCode;
        activeRuntimeUpstreamUserId = upstreamUserId;
        out.println("clientRequestId=" + clientRequestId);
        out.flush();
        return clientRequestId;
    }

    private RuntimeRequestFailure runtimeRequestFailure(
            NavigatorApiException error,
            String fallback,
            String clientRequestId) {
        String code = sanitizedRuntimeErrorCode(error, fallback);
        return new RuntimeRequestFailure(
                "sanitizedErrorCode=" + code + " clientRequestId=" + valueOrNull(clientRequestId));
    }

    private RuntimeRequestFailure runtimeStateAuditFailure(
            NavigatorApiException error,
            String fallback) {
        return new RuntimeRequestFailure(
                "sanitizedErrorCode=" + sanitizedRuntimeErrorCode(error, fallback));
    }

    private String sanitizedRuntimeErrorCode(NavigatorApiException error, String fallback) {
        String message = error != null ? error.getMessage() : null;
        if (hasText(message)) {
            Matcher matcher = Pattern.compile("[A-Z][A-Z0-9_]{2,127}").matcher(message);
            while (matcher.find()) {
                String candidate = matcher.group();
                if (SANITIZED_RUNTIME_ERROR_CODES.contains(candidate)) {
                    return candidate;
                }
            }
        }
        return fallback;
    }

    private String modelConfigId(CliArguments args) {
        String value = args.option("model-config-id");
        if (hasText(value)) {
            return value;
        }
        return config.get("NAVI_MODEL_CONFIG_ID");
    }

    private String modelVariant(CliArguments args) {
        String value = args.option("model-variant");
        if (hasText(value)) {
            return value;
        }
        value = args.option("model");
        if (hasText(value)) {
            return value;
        }
        value = config.get("NAVI_MODEL_VARIANT");
        if (hasText(value)) {
            return value;
        }
        return config.get("NAVI_MODEL");
    }

    private String agentCode(CliArguments args) {
        String value = args.option("agent-code");
        if (hasText(value)) {
            return value;
        }
        return requiredOptionOrConfig(args, "agent", "NAVI_AGENT_CODE", "agent");
    }

    private String explicitAgentCode(CliArguments args, String commandName) {
        String value = args.option("agent-code");
        if (hasText(value)) {
            return value;
        }
        value = args.option("agent");
        if (hasText(value)) {
            return value;
        }
        throw new UpstreamCliException(commandName + " requires --agent-code <agentId> (or --agent <agentId>). "
                + "Task polling does not fall back to NAVI_AGENT_CODE because shared profiles can point to a stale upstream Agent.");
    }

    private String upstreamUserId(CliArguments args) {
        return requiredOptionOrConfig(args, "upstream-user-id", "NAVI_UPSTREAM_USER_ID", "upstream user id");
    }

    private String sourceTenantId(CliArguments args) {
        String value = args.option("source-tenant-id");
        if (hasText(value)) {
            return value;
        }
        value = config.get("NAVI_SOURCE_TENANT_ID");
        if (hasText(value)) {
            return value;
        }
        value = config.get("NAVI_UPSTREAM_REF");
        if (hasText(value)) {
            return value;
        }
        throw new UpstreamCliException("source tenant id is required (--source-tenant-id or NAVI_SOURCE_TENANT_ID)");
    }

    private String adminKeyRequestCode(CliArguments args) {
        return requiredOptionOrConfig(args, "request-code", "NAVI_ADMIN_KEY_REQUEST_CODE", "admin key request code");
    }

    private String optionalUpstreamUserId(CliArguments args) {
        String value = args.option("upstream-user-id");
        if (hasText(value)) {
            return value;
        }
        return config.get("NAVI_UPSTREAM_USER_ID");
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
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed != null && !parsed.isEmpty() ? parsed : null;
        } catch (Exception e) {
            throw new UpstreamCliException("clientContext must be a valid JSON object");
        }
    }

    private void printTask(AgentTask task) {
        out.println("taskId=" + valueOrEmpty(task.getTaskId()));
        out.println("status=" + valueOrEmpty(task.getStatus()));
        out.println("contextId=" + valueOrEmpty(task.getContextId()));
        printTaskDiagnostics(task);
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

    private void printBusinessAgentBundle(BusinessAgentBundleDTO dto) {
        out.println("agent sync ok");
        out.println("clientAppId=" + valueOrEmpty(dto != null ? dto.getClientAppId() : null));
        out.println("agentId=" + valueOrEmpty(dto != null ? dto.getAgentId() : null));
        out.println("skillId=" + valueOrEmpty(dto != null ? dto.getSkillId() : null));
        out.println("ownerType=" + valueOrEmpty(dto != null ? dto.getOwnerType() : null));
        out.println("ownerId=" + valueOrEmpty(dto != null ? dto.getOwnerId() : null));
        out.println("workerId=" + valueOrEmpty(dto != null ? dto.getWorkerId() : null));
        out.println("defaultDirectoryId=" + valueOrEmpty(dto != null ? dto.getDefaultDirectoryId() : null));
        out.println("defaultModelConfigId=" + valueOrEmpty(dto != null ? dto.getDefaultModelConfigId() : null));
        out.println("enabled=" + (dto != null && Boolean.TRUE.equals(dto.getEnabled())));
        if (dto != null && dto.getSkillBundle() != null) {
            out.println("skillBundleStatus=" + valueOrEmpty(dto.getSkillBundle().getStatus()));
            if (dto.getSkillBundle().getMaterializeResult() != null) {
                out.println("skillBundleMaterializeStatus=" + valueOrEmpty(dto.getSkillBundle().getMaterializeResult().getStatus()));
            }
        }
    }

    private void printFunctionGrant(String prefix, ClientAppFunctionGrantDTO grant) {
        out.println(prefix
                + " grantId=" + valueOrEmpty(grant != null ? grant.getGrantId() : null)
                + " clientAppId=" + valueOrEmpty(grant != null ? grant.getClientAppId() : null)
                + " functionId=" + valueOrEmpty(grant != null ? grant.getFunctionId() : null)
                + " version=" + valueOrEmpty(grant != null ? grant.getVersion() : null)
                + " status=" + valueOrEmpty(grant != null ? grant.getStatus() : null));
    }

    private void printFunctionSummary(BusinessFunctionSummaryDTO function) {
        out.println("function"
                + " functionId=" + valueOrEmpty(function != null ? function.getFunctionId() : null)
                + " version=" + valueOrEmpty(function != null ? function.getVersion() : null)
                + " domain=" + valueOrEmpty(function != null ? function.getDomain() : null)
                + " name=" + valueOrEmpty(function != null ? function.getName() : null)
                + " riskLevel=" + valueOrEmpty(function != null ? function.getRiskLevel() : null)
                + " approvalRequired=" + (function != null && Boolean.TRUE.equals(function.getApprovalRequired()))
                + " idempotencyRequired=" + (function != null && Boolean.TRUE.equals(function.getIdempotencyRequired())));
    }

    private void printUpstreamRoute(String prefix, ClientAppUpstreamRouteDTO route) {
        out.println(prefix
                + " id=" + valueOrEmpty(route != null ? route.getId() : null)
                + " clientAppId=" + valueOrEmpty(route != null ? route.getClientAppId() : null)
                + " upstreamRef=" + valueOrEmpty(route != null ? route.getUpstreamRef() : null)
                + " baseUrl=" + valueOrEmpty(route != null ? route.getBaseUrl() : null)
                + " userTokenHeader=" + valueOrEmpty(route != null ? route.getUserTokenHeader() : null)
                + " status=" + valueOrEmpty(route != null ? route.getStatus() : null));
    }

    private void printSkillClearResult(String command, SkillClearResultDTO dto) {
        out.println(command + " ok");
        out.println("scope=" + valueOrEmpty(dto != null ? dto.getScope() : null));
        out.println("clientAppId=" + valueOrEmpty(dto != null ? dto.getClientAppId() : null));
        out.println("accountId=" + valueOrEmpty(dto != null ? dto.getAccountId() : null));
        out.println("skillId=" + valueOrEmpty(dto != null ? dto.getSkillId() : null));
        out.println("dryRun=" + (dto != null && dto.isDryRun()));
        out.println("executed=" + (dto != null && dto.isExecuted()));
        out.println("matchedSkillCount=" + (dto != null ? dto.getMatchedSkillCount() : 0));
        out.println("skillBundleCount=" + (dto != null ? dto.getSkillBundleCount() : 0));
        out.println("legacySkillCount=" + (dto != null ? dto.getLegacySkillCount() : 0));
        out.println("clientAppSkillGrantCount=" + (dto != null ? dto.getClientAppSkillGrantCount() : 0));
        out.println("skillFunctionAllowlistCount=" + (dto != null ? dto.getSkillFunctionAllowlistCount() : 0));
        out.println("materializedBundleCount=" + (dto != null ? dto.getMaterializedBundleCount() : 0));
        out.println("cacheCount=" + (dto != null ? dto.getCacheCount() : 0));
        out.println("workerClearStatus=" + valueOrEmpty(dto != null ? dto.getWorkerClearStatus() : null));
        out.println("workerStatusCode=" + valueOrEmpty(dto != null ? dto.getWorkerStatusCode() : null));
        if (dto != null && dto.getSkillIds() != null) {
            for (String skillId : dto.getSkillIds()) {
                out.println("matchedSkillId=" + valueOrEmpty(skillId));
            }
        }
    }

    private void printModelConfigGrant(String prefix, ClientAppModelConfigGrantDTO grant) {
        out.println(prefix
                + " id=" + valueOrEmpty(grant != null ? grant.getId() : null)
                + " clientAppId=" + valueOrEmpty(grant != null ? grant.getClientAppId() : null)
                + " modelConfigId=" + valueOrEmpty(grant != null ? grant.getModelConfigId() : null)
                + " name=" + valueOrEmpty(grant != null ? grant.getModelConfigName() : null)
                + " workerBackend=" + valueOrEmpty(grant != null ? grant.getWorkerBackend() : null)
                + " status=" + valueOrEmpty(grant != null ? grant.getStatus() : null)
                + " default=" + (grant != null && Boolean.TRUE.equals(grant.getIsDefault()))
                + " scope=" + valueOrEmpty(grant != null ? grant.getGrantScope() : null));
    }

    private void printUpstreamBootstrapRequest(String prefix, UpstreamBootstrapRequestDTO request) {
        out.println(prefix
                + " requestId=" + valueOrEmpty(request != null ? request.getRequestId() : null)
                + " codeSuffix=" + valueOrEmpty(request != null ? request.getRequestCodeSuffix() : null)
                + " upstreamSystemId=" + valueOrEmpty(request != null ? request.getUpstreamSystemId() : null)
                + " requestedTenantId=" + valueOrEmpty(request != null ? request.getRequestedTenantId() : null)
                + " multiTenant=" + (request != null && Boolean.TRUE.equals(request.getMultiTenant()))
                + " status=" + valueOrEmpty(request != null ? request.getStatus() : null));
        if (request == null) {
            return;
        }
        if (hasText(request.getDeniedReason())) {
            out.println(prefix + " deniedReason=" + redact(request.getDeniedReason()));
        }
        out.println(prefix + " authorizedTenantIds=" + joinList(request.getAuthorizedTenantIds()));
        out.println(prefix + " authorizedClientAppNamespace=" + valueOrEmpty(request.getAuthorizedClientAppNamespace()));
        out.println(prefix + " scopes=" + joinList(request.getScopes()));
        out.println(prefix + " requestExpiresAt=" + valueOrEmpty(request.getRequestExpiresAt()));
        out.println(prefix + " claimExpiresAt=" + valueOrEmpty(request.getClaimExpiresAt()));
        out.println(prefix + " adminCredentialExpiresAt=" + valueOrEmpty(request.getAdminCredentialExpiresAt()));
        out.println(prefix + " approvedAt=" + valueOrEmpty(request.getApprovedAt()));
        out.println(prefix + " deniedAt=" + valueOrEmpty(request.getDeniedAt()));
        out.println(prefix + " consumedAt=" + valueOrEmpty(request.getConsumedAt()));
    }

    private void printUpstreamAdminCredential(String prefix, UpstreamAdminCredentialDTO credential) {
        out.println(prefix
                + " credentialId=" + valueOrEmpty(credential != null ? credential.getCredentialId() : null)
                + " principalId=" + valueOrEmpty(credential != null ? credential.getPrincipalId() : null)
                + " upstreamSystemId=" + valueOrEmpty(credential != null ? credential.getUpstreamSystemId() : null)
                + " status=" + valueOrEmpty(credential != null ? credential.getStatus() : null));
        if (credential == null) {
            return;
        }
        out.println(prefix + " keyPrefix=" + SecretMasker.mask(credential.getCredentialKeyPrefix())
                + " keySuffix=" + SecretMasker.mask(credential.getCredentialKeySuffix()));
        out.println(prefix + " authorizedTenantIds=" + joinList(credential.getAuthorizedTenantIds()));
        out.println(prefix + " authorizedClientAppNamespace=" + valueOrEmpty(credential.getAuthorizedClientAppNamespace()));
        out.println(prefix + " scopes=" + joinList(credential.getScopes()));
        out.println(prefix + " expiresAt=" + valueOrEmpty(credential.getExpiresAt()));
        printCredentialExpiry(prefix, credential.getExpiresAt());
        out.println(prefix + " revokedAt=" + valueOrEmpty(credential.getRevokedAt()));
        out.println(prefix + " lastUsedAt=" + valueOrEmpty(credential.getLastUsedAt()));
        out.println(prefix + " sourceRequestId=" + valueOrEmpty(credential.getSourceRequestId()));
    }

    private void printRuntimeTokenExpiry(ClientAppRuntimeAccessTokenDTO token) {
        if (token == null) {
            out.println("runtimeToken.expiryStatus=UNKNOWN");
            out.println("runtimeToken.expiryAction=exchange runtime token from client app key-secret");
            return;
        }
        Long remainingSeconds = token.getExpiresInSeconds();
        LocalDateTime now = LocalDateTime.now();
        if (remainingSeconds == null && token.getExpiresAt() != null) {
            remainingSeconds = Duration.between(now, token.getExpiresAt()).getSeconds();
        }
        if (remainingSeconds == null) {
            out.println("runtimeToken.expiryStatus=UNKNOWN");
            out.println("runtimeToken.refresh=automatic when NAVI_CLIENT_APP_SECRET is present");
            return;
        }
        out.println("runtimeToken.expiryStatus=" + (remainingSeconds <= 0 ? "EXPIRED" : "OK"));
        out.println("runtimeToken.refresh=automatic when NAVI_CLIENT_APP_SECRET is present");
        if (remainingSeconds <= 0) {
            out.println("runtimeToken.expiryAction=run upstream runtime-token --write-profile with a valid ClientApp key-secret");
        }
    }

    private void printCredentialExpiry(String prefix, LocalDateTime expiresAt) {
        if (expiresAt == null) {
            out.println(prefix + " expiryStatus=NO_EXPIRY");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        Duration remaining = Duration.between(now, expiresAt);
        long remainingDays = remaining.toDays();
        out.println(prefix + " expiresInDays=" + remainingDays);
        if (!expiresAt.isAfter(now)) {
            out.println(prefix + " expiryStatus=EXPIRED");
            out.println(prefix + " expiryAction=rotate or re-issue provisioning credential before management operations");
            return;
        }
        if (remaining.compareTo(Duration.ofDays(14)) <= 0) {
            out.println(prefix + " expiryStatus=EXPIRING_SOON");
            out.println(prefix + " expiryAction=schedule credential rotation before provisioning changes");
            return;
        }
        out.println(prefix + " expiryStatus=OK");
    }

    private void printClientApp(String prefix, ClientAppDTO app) {
        out.println(prefix
                + " clientAppId=" + valueOrEmpty(app != null ? app.getClientAppId() : null)
                + " tenantId=" + valueOrEmpty(app != null ? app.getTenantId() : null)
                + " name=" + redact(app != null ? app.getName() : null)
                + " status=" + valueOrEmpty(app != null ? app.getStatus() : null)
                + " upstreamSystemId=" + valueOrEmpty(app != null ? app.getUpstreamSystemId() : null)
                + " namespace=" + valueOrEmpty(app != null ? app.getUpstreamClientAppNamespace() : null)
                + " upstreamRef=" + valueOrEmpty(app != null ? app.getUpstreamRef() : null));
    }

    private void writeProvisionedTenantProfiles(ProvisionedProfileTargets profiles,
                                                UpstreamTenantClientAppProvisioningDTO dto,
                                                String sourceSystem,
                                                String sourceTenantId) {
        Map<String, String> shared = provisionedProfileValues(dto, sourceSystem, sourceTenantId);
        Map<String, String> control = new LinkedHashMap<>(shared);
        control.put("NAVI_CONTROL_API_KEY", dto.getControlApiKey());
        Map<String, String> runtime = new LinkedHashMap<>(shared);
        runtime.put("NAVI_CLIENT_APP_KEY", dto.getClientAppKey());
        runtime.put("NAVI_CLIENT_APP_SECRET", dto.getClientAppSecret());
        runtime.put("NAVI_CLIENT_APP_ACCESS_TOKEN", "");

        config.writeProfileValues(profiles.platformControlProfile(), control, PLATFORM_CONTROL_PROFILE_FORBIDDEN);
        try {
            config.writeProfileValues(profiles.tenantRuntimeProfile(), runtime, TENANT_RUNTIME_PROFILE_FORBIDDEN);
        } catch (UpstreamCliException e) {
            throw new UpstreamCliException("tenant runtime profile was not written; the platform control profile remains private and no combined credential profile was created", e);
        }
    }

    private void printRuntimeAudits(RuntimeRequestAuditPageDTO page) {
        List<RuntimeRequestAuditDTO> items = page.getItems() != null ? page.getItems() : List.of();
        out.println("auditCount=" + items.size());
        out.println("auditLimit=" + page.getLimit());
        for (int i = 0; i < items.size(); i++) {
            RuntimeRequestAuditDTO audit = items.get(i);
            String prefix = "audit[" + i + "].";
            out.println(prefix + "clientRequestId=" + valueOrNull(audit.getClientRequestId()));
            out.println(prefix + "parentClientRequestId=" + valueOrNull(audit.getParentClientRequestId()));
            out.println(prefix + "correlationId=" + valueOrNull(audit.getCorrelationId()));
            out.println(prefix + "operation=" + valueOrUnknown(audit.getOperation()));
            out.println(prefix + "receivedAt=" + valueOrUnknown(audit.getReceivedAt()));
            out.println(prefix + "completedAt=" + valueOrNull(audit.getCompletedAt()));
            out.println(prefix + "terminal=" + booleanOrUnknown(audit.getTerminal()));
            out.println(prefix + "result=" + valueOrUnknown(audit.getResult()));
            out.println(prefix + "sanitizedErrorCode=" + valueOrNull(audit.getSanitizedErrorCode()));
            out.println(prefix + "safeErrorSummary=" + valueOrNull(audit.getSafeErrorSummary()));
            out.println(prefix + "httpRequestReceived=" + booleanOrUnknown(audit.getHttpRequestReceived()));
            out.println(prefix + "runtimeTokenRequestReceived=" + booleanOrUnknown(audit.getRuntimeTokenRequestReceived()));
            out.println(prefix + "runtimeTokenIssued=" + booleanOrUnknown(audit.getRuntimeTokenIssued()));
            out.println(prefix + "runtimeTokenExchangeCount=" + valueOrUnknown(audit.getRuntimeTokenExchangeCount()));
            out.println(prefix + "standardAskRequestReceived=" + booleanOrUnknown(audit.getStandardAskRequestReceived()));
            out.println(prefix + "admissionCompleted=" + booleanOrUnknown(audit.getAdmissionCompleted()));
            out.println(prefix + "taskCreated=" + booleanOrUnknown(audit.getTaskCreated()));
            out.println(prefix + "taskTokenIssued=" + booleanOrUnknown(audit.getTaskTokenIssued()));
            out.println(prefix + "safeSmokeRequestReceived=" + booleanOrUnknown(audit.getSafeSmokeRequestReceived()));
            out.println(prefix + "syntheticEvidenceCreated=" + booleanOrUnknown(audit.getSyntheticEvidenceCreated()));
            out.println(prefix + "taskId=" + valueOrNull(audit.getTaskId()));
            out.println(prefix + "agentCode=" + valueOrNull(audit.getAgentCode()));
            out.println(prefix + "upstreamUserId=" + valueOrNull(audit.getUpstreamUserId()));
            out.println(prefix + "physicalWorkerId=" + valueOrNull(audit.getPhysicalWorkerId()));
            out.println(prefix + "modelConfigId=" + valueOrNull(audit.getModelConfigId()));
            out.println(prefix + "modelVariant=" + valueOrNull(audit.getModelVariant()));
            out.println(prefix + "status=" + valueOrUnknown(audit.getStatus()));
            out.println(prefix + "requestedToolCount=" + valueOrUnknown(audit.getRequestedToolCount()));
            out.println(prefix + "effectiveToolCount=" + valueOrUnknown(audit.getEffectiveToolCount()));
            out.println(prefix + "toolScopeKind=" + valueOrUnknown(audit.getToolScopeKind()));
            out.println(prefix + "toolScopeSource=" + valueOrUnknown(audit.getToolScopeSource()));
            out.println(prefix + "requestedFunctionCount=" + valueOrUnknown(audit.getRequestedFunctionCount()));
            out.println(prefix + "effectiveFunctionCount=" + valueOrUnknown(audit.getEffectiveFunctionCount()));
            out.println(prefix + "functionScopeSource=" + valueOrUnknown(audit.getFunctionScopeSource()));
            out.println(prefix + "taskTokenFunctionScopeEmpty="
                    + booleanOrUnknown(audit.getTaskTokenFunctionScopeEmpty()));
            out.println(prefix + "taskTokenStatus=" + valueOrUnknown(audit.getTaskTokenStatus()));
            out.println(prefix + "runtimeDispatched=" + booleanOrUnknown(audit.getRuntimeDispatched()));
            out.println(prefix + "modelDispatched=" + booleanOrUnknown(audit.getModelDispatched()));
            out.println(prefix + "businessFunctionDispatched="
                    + booleanOrUnknown(audit.getBusinessFunctionDispatched()));
            out.println(prefix + "dispatchCount=" + valueOrUnknown(audit.getDispatchCount()));
            out.println(prefix + "retryCount=" + valueOrUnknown(audit.getRetryCount()));
            out.println(prefix + "recoveryCount=" + valueOrUnknown(audit.getRecoveryCount()));
            out.println(prefix + "taskFacts=" + valueOrNull(audit.getTaskFacts()));
            out.println(prefix + "auditSideEffects=" + valueOrNull(audit.getAuditSideEffects()));
            List<RuntimeRequestAuditStageDTO> stages = audit.getStages() != null ? audit.getStages() : List.of();
            out.println(prefix + "stageCount=" + stages.size());
            for (int j = 0; j < stages.size(); j++) {
                RuntimeRequestAuditStageDTO stage = stages.get(j);
                String stagePrefix = prefix + "stages[" + j + "].";
                out.println(stagePrefix + "stage=" + valueOrUnknown(stage.getStage()));
                out.println(stagePrefix + "status=" + valueOrUnknown(stage.getStatus()));
                out.println(stagePrefix + "sanitizedErrorCode=" + valueOrNull(stage.getSanitizedErrorCode()));
                out.println(stagePrefix + "occurredAt=" + valueOrUnknown(stage.getOccurredAt()));
            }
        }
    }

    private void printRuntimeBindingAudit(RuntimeBindingAuditDTO audit) {
        out.println("observedAt=" + valueOrUnknown(audit.getObservedAt()));
        out.println("tenant=" + valueOrUnknown(audit.getTenant()));
        out.println("upstreamUserId=" + valueOrUnknown(audit.getUpstreamUserId()));
        out.println("agentCode=" + valueOrUnknown(audit.getAgentCode()));
        out.println("agentEnabled=" + booleanOrUnknown(audit.getAgentEnabled()));
        out.println("modelConfigId=" + valueOrUnknown(audit.getModelConfigId()));
        out.println("modelVariant=" + valueOrUnknown(audit.getModelVariant()));
        out.println("modelBackend=" + valueOrUnknown(audit.getModelBackend()));
        out.println("directoryId=" + valueOrUnknown(audit.getDirectoryId()));
        out.println("directoryEnabled=" + booleanOrUnknown(audit.getDirectoryEnabled()));
        out.println("workerHost=" + valueOrUnknown(audit.getWorkerHost()));
        out.println("physicalWorkerId=" + valueOrUnknown(audit.getPhysicalWorkerId()));
        out.println("physicalWorkerStatus=" + valueOrUnknown(audit.getPhysicalWorkerStatus()));
        out.println("directoryRolePort=" + valueOrUnknown(audit.getDirectoryRolePort()));
        out.println("codexRolePort=" + valueOrUnknown(audit.getCodexRolePort()));
        out.println("codexRoleSource=" + valueOrNull(audit.getCodexRoleSource()));
        out.println("codexRoleSamePhysicalWorker=" + booleanOrUnknown(audit.getCodexRoleSamePhysicalWorker()));
        out.println("activeTaskCount=" + valueOrUnknown(audit.getActiveTaskCount()));
        printRuntimeStateAuditSideEffects(
                audit.getAuditAccessTokenIssued(),
                audit.getAuditRuntimeTokenIssued(),
                audit.getAuditTaskTokenIssued(),
                audit.getTaskCreated(),
                audit.getContextCreated(),
                audit.getSessionCreated(),
                audit.getModelDispatched(),
                audit.getBusinessFunctionDispatched(),
                audit.getRecoveryTriggered(),
                audit.getProvisioningResourceChanged());
    }

    private void printRuntimeTaskAudit(RuntimeTaskAuditDTO audit) {
        out.println("observedAt=" + valueOrUnknown(audit.getObservedAt()));
        out.println("taskId=" + valueOrUnknown(audit.getTaskId()));
        out.println("terminal=" + booleanOrUnknown(audit.getTerminal()));
        out.println("status=" + valueOrUnknown(audit.getStatus()));
        out.println("sanitizedErrorCode=" + valueOrNull(audit.getSanitizedErrorCode()));
        out.println("taskTokenStatus=" + valueOrUnknown(audit.getTaskTokenStatus()));
        out.println("activeTaskRegistrationPresent="
                + booleanOrUnknown(audit.getActiveTaskRegistrationPresent()));
        out.println("dispatchCount=" + valueOrUnknown(audit.getDispatchCount()));
        out.println("retryCount=" + valueOrUnknown(audit.getRetryCount()));
        out.println("recoveryCount=" + valueOrUnknown(audit.getRecoveryCount()));
        out.println("physicalWorkerId=" + valueOrUnknown(audit.getPhysicalWorkerId()));
        out.println("modelConfigId=" + valueOrUnknown(audit.getModelConfigId()));
        out.println("modelVariant=" + valueOrUnknown(audit.getModelVariant()));
        out.println("createdAt=" + valueOrUnknown(audit.getCreatedAt()));
        out.println("completedAt=" + valueOrNull(audit.getCompletedAt()));
        List<RuntimeTaskAuditStageDTO> stages =
                audit.getTerminalStages() != null ? audit.getTerminalStages() : List.of();
        out.println("terminalStageCount=" + stages.size());
        for (int i = 0; i < stages.size(); i++) {
            RuntimeTaskAuditStageDTO stage = stages.get(i);
            String prefix = "terminalStages[" + i + "].";
            out.println(prefix + "stage=" + valueOrUnknown(stage.getStage()));
            out.println(prefix + "status=" + valueOrUnknown(stage.getStatus()));
            out.println(prefix + "sanitizedErrorCode=" + valueOrNull(stage.getSanitizedErrorCode()));
            out.println(prefix + "occurredAt=" + valueOrUnknown(stage.getOccurredAt()));
        }
        printRuntimeStateAuditSideEffects(
                audit.getAuditAccessTokenIssued(),
                audit.getAuditRuntimeTokenIssued(),
                audit.getAuditTaskTokenIssued(),
                audit.getTaskCreated(),
                audit.getContextCreated(),
                audit.getSessionCreated(),
                audit.getModelDispatched(),
                audit.getBusinessFunctionDispatched(),
                audit.getRecoveryTriggered(),
                audit.getProvisioningResourceChanged());
    }

    private void printRuntimeStateAuditSideEffects(
            Boolean auditAccessTokenIssued,
            Boolean auditRuntimeTokenIssued,
            Boolean auditTaskTokenIssued,
            Boolean taskCreated,
            Boolean contextCreated,
            Boolean sessionCreated,
            Boolean modelDispatched,
            Boolean businessFunctionDispatched,
            Boolean recoveryTriggered,
            Boolean provisioningResourceChanged) {
        out.println("auditAccessTokenIssued=" + booleanOrUnknown(auditAccessTokenIssued));
        out.println("auditRuntimeTokenIssued=" + booleanOrUnknown(auditRuntimeTokenIssued));
        out.println("auditTaskTokenIssued=" + booleanOrUnknown(auditTaskTokenIssued));
        out.println("taskCreated=" + booleanOrUnknown(taskCreated));
        out.println("contextCreated=" + booleanOrUnknown(contextCreated));
        out.println("sessionCreated=" + booleanOrUnknown(sessionCreated));
        out.println("modelDispatched=" + booleanOrUnknown(modelDispatched));
        out.println("businessFunctionDispatched=" + booleanOrUnknown(businessFunctionDispatched));
        out.println("recoveryTriggered=" + booleanOrUnknown(recoveryTriggered));
        out.println("provisioningResourceChanged=" + booleanOrUnknown(provisioningResourceChanged));
    }

    private void printSystemAdminClientAppScope(UpstreamAdminClientAppScopeDTO scope) {
        out.println("scopeCredentialLane=" + valueOrEmpty(scope != null ? scope.getCredentialLane() : null));
        out.println("scopePrincipalType=" + valueOrEmpty(scope != null ? scope.getPrincipalType() : null));
        out.println("scopeUpstreamSystemId=" + valueOrEmpty(scope != null ? scope.getUpstreamSystemId() : null));
        out.println("scopeTenantId=" + valueOrEmpty(scope != null ? scope.getTenantId() : null));
        out.println("scopeClientAppId=" + valueOrEmpty(scope != null ? scope.getClientAppId() : null));
        out.println("scopeClientAppNamespace=" + valueOrEmpty(scope != null ? scope.getClientAppNamespace() : null));
        out.println("scopeTargetOwnerType=" + valueOrEmpty(scope != null ? scope.getTargetOwnerType() : null));
        out.println("scopeTargetOwnerId=" + valueOrEmpty(scope != null ? scope.getTargetOwnerId() : null));
        out.println("scopeAuthorizationChecks=" + joinList(scope != null ? scope.getAuthorizationChecks() : null));
    }

    private Map<String, String> provisionedProfileValues(UpstreamTenantClientAppProvisioningDTO dto,
                                                           String sourceSystem,
                                                           String sourceTenantId) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("NAVI_BASE_URL", config.required("NAVI_BASE_URL", "Navigator base URL"));
        values.put("NAVI_TENANT_ID", emptyIfNull(dto.getNavigatorTenantId()));
        values.put("NAVI_CLIENT_APP_ID", emptyIfNull(dto.getClientAppId()));
        values.put("NAVI_UPSTREAM_SYSTEM_ID", emptyIfNull(firstNonBlank(dto.getUpstreamSystemId(), sourceSystem)));
        values.put("NAVI_SOURCE_TENANT_ID", emptyIfNull(firstNonBlank(dto.getSourceTenantId(), sourceTenantId)));
        values.put("NAVI_UPSTREAM_REF", emptyIfNull(firstNonBlank(dto.getUpstreamRef(), sourceTenantId)));
        values.put("NAVI_UPSTREAM_NAMESPACE", emptyIfNull(dto.getUpstreamNamespace()));
        values.put("NAVI_CLIENT_APP_CAPABILITY_DOMAIN", emptyIfNull(firstNonBlank(dto.getClientAppCapabilityDomain(), dto.getCapabilityDomain())));
        values.put("NAVI_AGENT_CODE", emptyIfNull(firstNonBlank(dto.getAgentCode(), dto.getRootAgentId())));
        values.put("NAVI_ROOT_AGENT_ID", emptyIfNull(dto.getRootAgentId()));
        values.put("NAVI_MODEL_CONFIG_ID", emptyIfNull(dto.getModelConfigId()));
        values.put("NAVI_SKILL_ID", emptyIfNull(dto.getSkillId()));
        values.put("NAVI_WORKER_POOL_ID", emptyIfNull(dto.getWorkerPoolId()));
        values.put("NAVI_WORKER_BACKEND", emptyIfNull(dto.getWorkerBackend()));
        values.put("NAVI_PHYSICAL_WORKER_ID", emptyIfNull(dto.getPhysicalWorkerId()));
        values.put("NAVI_DIRECTORY_ID", emptyIfNull(dto.getDirectoryId()));
        values.put("NAVI_BIZ_WORKER_BASE_URL", emptyIfNull(dto.getBizWorkerBaseUrl()));
        return values;
    }

    private String provisionedControlStoredKeys(UpstreamTenantClientAppProvisioningDTO dto) {
        List<String> keys = new ArrayList<>(provisionedSharedStoredKeys());
        if (hasText(dto.getControlApiKey())) {
            keys.add("NAVI_CONTROL_API_KEY");
        }
        return String.join(",", keys);
    }

    private String provisionedRuntimeStoredKeys(UpstreamTenantClientAppProvisioningDTO dto) {
        List<String> keys = new ArrayList<>(provisionedSharedStoredKeys());
        if (hasText(dto.getClientAppKey())) {
            keys.add("NAVI_CLIENT_APP_KEY");
        }
        if (hasText(dto.getClientAppSecret())) {
            keys.add("NAVI_CLIENT_APP_SECRET");
        }
        keys.add("NAVI_CLIENT_APP_ACCESS_TOKEN");
        return String.join(",", keys);
    }

    private List<String> provisionedSharedStoredKeys() {
        return List.of(
                "NAVI_BASE_URL",
                "NAVI_TENANT_ID",
                "NAVI_CLIENT_APP_ID",
                "NAVI_UPSTREAM_SYSTEM_ID",
                "NAVI_SOURCE_TENANT_ID",
                "NAVI_UPSTREAM_REF",
                "NAVI_UPSTREAM_NAMESPACE",
                "NAVI_CLIENT_APP_CAPABILITY_DOMAIN",
                "NAVI_AGENT_CODE",
                "NAVI_ROOT_AGENT_ID",
                "NAVI_MODEL_CONFIG_ID",
                "NAVI_SKILL_ID",
                "NAVI_WORKER_POOL_ID",
                "NAVI_WORKER_BACKEND",
                "NAVI_PHYSICAL_WORKER_ID",
                "NAVI_DIRECTORY_ID",
                "NAVI_BIZ_WORKER_BASE_URL");
    }

    private boolean isCredentialsNotReplayable(UpstreamTenantClientAppProvisioningDTO dto) {
        return dto != null
                && (CREDENTIALS_NOT_REPLAYABLE.equals(dto.getStatus())
                || CREDENTIALS_NOT_REPLAYABLE.equals(dto.getErrorCode()));
    }

    private void printUpstreamTenantClientAppProvisioning(UpstreamTenantClientAppProvisioningDTO dto) {
        out.println("navigatorTenantId=" + valueOrEmpty(dto != null ? dto.getNavigatorTenantId() : null));
        out.println("clientAppId=" + valueOrEmpty(dto != null ? dto.getClientAppId() : null));
        out.println("clientAppName=" + redact(dto != null ? dto.getClientAppName() : null));
        out.println("capabilityDomain=" + valueOrEmpty(dto != null ? dto.getCapabilityDomain() : null));
        out.println("clientAppCapabilityDomain=" + valueOrEmpty(dto != null ? dto.getClientAppCapabilityDomain() : null));
        out.println("upstreamSystemId=" + valueOrEmpty(dto != null ? dto.getUpstreamSystemId() : null));
        out.println("sourceTenantId=" + valueOrEmpty(dto != null ? dto.getSourceTenantId() : null));
        out.println("upstreamRef=" + valueOrEmpty(dto != null ? dto.getUpstreamRef() : null));
        out.println("upstreamNamespace=" + valueOrEmpty(dto != null ? dto.getUpstreamNamespace() : null));
        out.println("clientAppKey=" + SecretMasker.mask(dto != null ? dto.getClientAppKey() : null));
        out.println("clientAppSecret=" + SecretMasker.mask(dto != null ? dto.getClientAppSecret() : null));
        out.println("controlApiKey=" + SecretMasker.mask(dto != null ? dto.getControlApiKey() : null));
        out.println("agentCode=" + valueOrEmpty(dto != null ? dto.getAgentCode() : null));
        out.println("rootAgentId=" + valueOrEmpty(dto != null ? dto.getRootAgentId() : null));
        out.println("modelConfigId=" + valueOrEmpty(dto != null ? dto.getModelConfigId() : null));
        out.println("skillId=" + valueOrEmpty(dto != null ? dto.getSkillId() : null));
        out.println("workerPoolId=" + valueOrEmpty(dto != null ? dto.getWorkerPoolId() : null));
        out.println("workerBackend=" + valueOrEmpty(dto != null ? dto.getWorkerBackend() : null));
        out.println("physicalWorkerId=" + valueOrEmpty(dto != null ? dto.getPhysicalWorkerId() : null));
        out.println("directoryId=" + valueOrEmpty(dto != null ? dto.getDirectoryId() : null));
        out.println("bizWorkerBaseUrl=" + redact(dto != null ? dto.getBizWorkerBaseUrl() : null));
        out.println("bindingVersion=" + valueOrEmpty(dto != null ? dto.getBindingVersion() : null));
        out.println("status=" + valueOrEmpty(dto != null ? dto.getStatus() : null));
        out.println("errorCode=" + valueOrEmpty(dto != null ? dto.getErrorCode() : null));
        out.println("activationReady=" + (dto != null && Boolean.TRUE.equals(dto.getActivationReady())));
        out.println("credentialsReplayable=" + (dto != null && Boolean.TRUE.equals(dto.getCredentialsReplayable())));
        if (dto != null && hasText(dto.getMessage())) {
            out.println("message=" + redact(dto.getMessage()));
        }
        if (dto != null && hasText(dto.getRemediationHint())) {
            out.println("remediationHint=" + redact(dto.getRemediationHint()));
        }
        out.println("created=" + (dto != null && Boolean.TRUE.equals(dto.getCreated())));
        out.println("rotated=" + (dto != null && Boolean.TRUE.equals(dto.getRotated())));
        if (dto != null && dto.getBlockers() != null) {
            for (String blocker : dto.getBlockers()) {
                out.println("blocker=" + redact(blocker));
            }
        }
        if (dto != null && dto.getMissingFields() != null) {
            for (String missingField : dto.getMissingFields()) {
                out.println("missingField=" + valueOrEmpty(missingField));
            }
        }
        if (dto != null) {
            out.println("requiredScopes=" + joinList(dto.getRequiredScopes()));
            out.println("actualScopes=" + joinList(dto.getActualScopes()));
            out.println("authorizedTenantIds=" + joinList(dto.getAuthorizedTenantIds()));
        }
    }

    private void printAccountContextFileMetadata(AccountContextFileDTO file) {
        out.println("file name=" + valueOrEmpty(file != null ? file.getFileName() : null)
                + " exists=" + (file != null && file.isExists())
                + " writable=" + (file != null && file.isWritable())
                + " size=" + (file != null ? file.getSize() : 0)
                + " lineCount=" + (file != null ? file.getLineCount() : 0)
                + " truncated=" + (file != null && file.isTruncated())
                + " sha256=" + valueOrEmpty(file != null ? file.getSha256() : null));
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

    private <T> T readJsonFile(String file, Class<T> type) throws Exception {
        Path path = cwd.resolve(file).normalize();
        if (!Files.isRegularFile(path)) {
            throw new UpstreamCliException("json file not found: " + path);
        }
        return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), type);
    }

    private Map<String, Object> readJsonMap(String file) throws Exception {
        Path path = cwd.resolve(file).normalize();
        if (!Files.isRegularFile(path)) {
            throw new UpstreamCliException("json file not found: " + path);
        }
        return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), new TypeReference<>() {});
    }

    private Map<String, String> readJsonStringMap(String file) throws Exception {
        Path path = cwd.resolve(file).normalize();
        if (!Files.isRegularFile(path)) {
            throw new UpstreamCliException("json file not found: " + path);
        }
        return objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), new TypeReference<>() {});
    }

    private WorkerHostPlan normalizeWorkerHostManifest(WorkerHostManifest manifest) {
        if (manifest == null) {
            throw new UpstreamCliException("worker host manifest is empty");
        }
        String workerHostId = requiredValue(manifest.getWorkerHostId(), "workerHostId is required");
        String hostUrl = normalizeWorkerHostUrl(requiredValue(manifest.getHostUrl(), "hostUrl is required"));
        Integer defaultPort = requireValidPort(manifest.getPort(), "port");
        Map<String, WorkerHostManifest.WorkerSpec> workers = manifest.getWorkers() != null
                ? manifest.getWorkers()
                : Map.of();
        Set<String> allowedKeys = Set.of("claudeCode", "codex", "biz");
        for (String key : workers.keySet()) {
            if (!allowedKeys.contains(key)) {
                throw new UpstreamCliException("unsupported worker-host worker key: " + key);
            }
        }

        WorkerHostManifest.WorkerSpec claudeSpec = workerSpec(workers, "claudeCode");
        if (Boolean.FALSE.equals(claudeSpec.getEnabled())) {
            throw new UpstreamCliException("workers.claudeCode is required and cannot be disabled");
        }
        WorkerRolePlan claude = new WorkerRolePlan(
                claudeSpec.getWorkerId(),
                workerBaseUrl(hostUrl, firstNonBlank(claudeSpec.getBaseUrlOverride(), null),
                        claudeSpec.getPort() != null ? requireValidPort(claudeSpec.getPort(), "workers.claudeCode.port") : defaultPort),
                claudeSpec);

        WorkerRolePlan codex = null;
        WorkerHostManifest.WorkerSpec codexSpec = workers.get("codex");
        if (codexSpec != null && Boolean.TRUE.equals(codexSpec.getEnabled())) {
            if (hasText(codexSpec.getWorkerId())) {
                throw new UpstreamCliException("workers.codex.workerId is not supported in Navi-routed mode; set workers.claudeCode.workerId or --worker-id and configure workers.codex.port/baseUrlOverride");
            }
            codex = new WorkerRolePlan(
                    claude.workerId,
                    workerBaseUrl(hostUrl, codexSpec.getBaseUrlOverride(), requireRolePort(codexSpec, "codex")),
                    codexSpec);
        }

        WorkerRolePlan biz = null;
        WorkerHostManifest.WorkerSpec bizSpec = workers.get("biz");
        if (bizSpec != null && Boolean.TRUE.equals(bizSpec.getEnabled())) {
            biz = new WorkerRolePlan(
                    firstNonBlank(bizSpec.getWorkerId(), defaultRoleWorkerId(workerHostId, "biz")),
                    workerBaseUrl(hostUrl, bizSpec.getBaseUrlOverride(), requireRolePort(bizSpec, "biz")),
                    bizSpec);
        }

        return new WorkerHostPlan(workerHostId, hostUrl,
                hasText(manifest.getInstall()) ? manifest.getInstall() : "none",
                manifest.getWslDistro(),
                manifest.getWslUser(),
                claude, codex, biz);
    }

    private WorkerHostManifest.WorkerSpec workerSpec(Map<String, WorkerHostManifest.WorkerSpec> workers, String key) {
        WorkerHostManifest.WorkerSpec spec = workers.get(key);
        return spec != null ? spec : new WorkerHostManifest.WorkerSpec();
    }

    private String normalizeWorkerHostUrl(String hostUrl) {
        String value = hostUrl.trim();
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new UpstreamCliException("hostUrl is invalid: " + hostUrl);
        }
        if (!hasText(uri.getScheme()) || !hasText(uri.getHost())) {
            throw new UpstreamCliException("hostUrl must include scheme and host");
        }
        if (uri.getPort() >= 0) {
            throw new UpstreamCliException("hostUrl must not include a port; use top-level port or worker port");
        }
        String path = uri.getRawPath();
        if (hasText(path) && !"/".equals(path)) {
            throw new UpstreamCliException("hostUrl must not include a path; use baseUrlOverride for advanced routing");
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private Integer requireRolePort(WorkerHostManifest.WorkerSpec spec, String role) {
        if (hasText(spec.getBaseUrlOverride())) {
            return spec.getPort();
        }
        return requireValidPort(spec.getPort(), "workers." + role + ".port");
    }

    private Integer requireValidPort(Integer port, String field) {
        if (port == null) {
            throw new UpstreamCliException(field + " is required");
        }
        if (port < 1 || port > 65535) {
            throw new UpstreamCliException(field + " must be between 1 and 65535");
        }
        return port;
    }

    private String workerBaseUrl(String hostUrl, String baseUrlOverride, Integer port) {
        if (hasText(baseUrlOverride)) {
            return baseUrlOverride.trim();
        }
        return hostUrl + ":" + port;
    }

    private String defaultRoleWorkerId(String workerHostId, String role) {
        return workerHostId + "-" + role;
    }

    private Map<String, Object> buildClaudeWorkerBody(WorkerHostPlan plan) {
        Map<String, Object> body = new LinkedHashMap<>();
        WorkerHostManifest.WorkerSpec spec = plan.claudeCode.spec;
        body.put("name", firstNonBlank(spec.getName(), plan.workerHostId + " Claude Code Worker"));
        body.put("baseUrl", plan.claudeCode.baseUrl);
        putIfHasText(body, "authMode", spec.getAuthMode());
        putIfHasText(body, "authToken", resolveWorkerSecret(spec.getAuthToken(), spec.getAuthTokenEnv()));
        if (plan.codex != null) {
            Map<String, Object> codexConfig = new LinkedHashMap<>();
            codexConfig.put("baseUrl", plan.codex.baseUrl);
            putIfHasText(codexConfig, "authToken", resolveWorkerSecret(
                    plan.codex.spec.getAuthToken(), plan.codex.spec.getAuthTokenEnv()));
            putIfHasText(codexConfig, "model", plan.codex.spec.getModel());
            body.put("codexConfig", codexConfig);
        }
        return body;
    }

    private Map<String, Object> buildBizWorkerIdentityBody(WorkerHostPlan plan) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", plan.biz.workerId);
        body.put("workerBackend", "LANGGRAPH_BIZ");
        body.put("baseUrl", plan.biz.baseUrl);
        putIfHasText(body, "version", plan.biz.spec.getVersion());
        putIfHasText(body, "identityToken", resolveWorkerSecret(
                firstNonBlank(plan.biz.spec.getIdentityToken(), plan.biz.spec.getAuthToken()),
                firstNonBlank(plan.biz.spec.getIdentityTokenEnv(), plan.biz.spec.getAuthTokenEnv())));
        return body;
    }

    private String resolveWorkerSecret(String inlineSecret, String envName) {
        if (hasText(inlineSecret)) {
            return inlineSecret;
        }
        if (!hasText(envName)) {
            return null;
        }
        String value = env.get(envName);
        if (!hasText(value)) {
            throw new UpstreamCliException("environment variable " + envName + " is required");
        }
        return value;
    }

    private void putIfHasText(Map<String, Object> body, String key, String value) {
        if (hasText(value)) {
            body.put(key, value);
        }
    }

    private void printWorkerHostRole(String role, String workerId, String baseUrl, String source) {
        out.println("workerRole role=" + role
                + " workerId=" + valueOrEmpty(workerId)
                + " baseUrl=" + redact(baseUrl)
                + " source=" + source);
    }

    private void printJson(Object value) throws Exception {
        JsonNode redacted = SecretMasker.redactJson(objectMapper, value, this::redact);
        out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(redacted));
    }

    private void printWorker(Worker worker) {
        out.println("worker workerId=" + valueOrEmpty(worker != null ? worker.getWorkerId() : null)
                + " name=" + redact(worker != null ? worker.getName() : null)
                + " baseUrl=" + redact(worker != null ? worker.getBaseUrl() : null)
                + " status=" + valueOrEmpty(worker != null ? worker.getStatus() : null)
                + " authMode=" + valueOrEmpty(worker != null ? worker.getAuthMode() : null));
    }

    private void printDirectory(Directory dir) {
        out.println("directory directoryId=" + valueOrEmpty(dir != null ? dir.getDirectoryId() : null)
                + " workerId=" + valueOrEmpty(dir != null ? dir.getWorkerId() : null)
                + " ownerType=" + valueOrEmpty(dir != null ? dir.getOwnerType() : null)
                + " ownerId=" + valueOrEmpty(dir != null ? dir.getOwnerId() : null)
                + " workspaceScope=" + valueOrEmpty(dir != null ? dir.getWorkspaceScope() : null)
                + " resolverType=" + valueOrEmpty(dir != null ? dir.getResolverType() : null)
                + " enabled=" + valueOrEmpty(dir != null ? dir.getEnabled() : null)
                + " projectName=" + redact(dir != null ? dir.getProjectName() : null)
                + " path=" + redact(dir != null ? dir.getPath() : null));
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
                    + " eventKind=" + valueOrEmpty(message.getEventKind())
                    + " progressType=" + valueOrEmpty(message.getProgressType())
                    + " status=" + valueOrEmpty(message.getStatus())
                    + " terminal=" + Boolean.TRUE.equals(message.getTerminal())
                    + " terminalStatus=" + valueOrEmpty(message.getTerminalStatus())
                    + " content=" + redact(truncate(message.getContent(), 500)));
            if (message.getReportRefs() != null) {
                for (TaskEvidence.ReportRef ref : message.getReportRefs()) {
                    out.println("messageReportRef messageId=" + valueOrEmpty(message.getMessageId())
                            + " type=" + valueOrEmpty(ref != null ? ref.getType() : null)
                            + " ref=" + redact(valueOrEmpty(ref != null ? ref.getRef() : null))
                            + " frameId=" + redact(valueOrEmpty(ref != null ? ref.getFrameId() : null)));
                }
            }
            if (message.getArtifactRefs() != null) {
                for (TaskEvidence.ArtifactRef ref : message.getArtifactRefs()) {
                    out.println("messageArtifactRef messageId=" + valueOrEmpty(message.getMessageId())
                            + " path=" + redact(valueOrEmpty(ref != null ? ref.getPath() : null))
                            + " ref=" + redact(valueOrEmpty(ref != null ? ref.getRef() : null)));
                }
            }
        }
    }

    private String redact(String text) {
        String redacted = SecretMasker.redactKnownSecrets(valueOrEmpty(text), config.sensitiveValues());
        return redacted
                .replaceAll("(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(access[_-]?token\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(token\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(client[_-]?secret\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(secret\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-[REDACTED]");
    }

    private void printTaskDiagnostics(AgentTask task) {
        printTaskDiagnostics(null, task);
    }

    private void printTaskDiagnostics(TaskDiagnostics diagnostics) {
        if (diagnostics == null) {
            return;
        }
        out.println("taskId=" + valueOrEmpty(diagnostics.getTaskId()));
        out.println("contextId=" + valueOrEmpty(diagnostics.getContextId()));
        out.println("status=" + valueOrEmpty(diagnostics.getStatus()));
        out.println("terminal=" + diagnostics.isTerminal());
        printDiagnostic("terminalStatus", diagnostics.getTerminalStatus());
        printDiagnostic("submittedAt", diagnostics.getSubmittedAt() != null ? diagnostics.getSubmittedAt().toString() : null);
        printDiagnostic("workerStartedAt", diagnostics.getWorkerStartedAt() != null ? diagnostics.getWorkerStartedAt().toString() : null);
        printDiagnostic("lastObservedAt", diagnostics.getLastObservedAt() != null ? diagnostics.getLastObservedAt().toString() : null);
        if (diagnostics.getMessagesCount() != null) {
            out.println("messagesCount=" + diagnostics.getMessagesCount());
        }
        printDiagnostic("providerTaskId", diagnostics.getProviderTaskId());
        printDiagnostic("workerTaskId", diagnostics.getWorkerTaskId());
        if (diagnostics.getLastAckedSeq() != null) {
            out.println("lastAckedSeq=" + diagnostics.getLastAckedSeq());
        }
        printDiagnostic("modelConfigId", diagnostics.getModelConfigId());
        printDiagnostic("modelConfigSource", diagnostics.getModelConfigSource());
        printDiagnostic("workerBackend", diagnostics.getWorkerBackend());
        printDiagnostic("providerType", diagnostics.getProviderType());
        printDiagnostic("taskSource", diagnostics.getTaskSource());
        printDiagnostic("workerSource", diagnostics.getWorkerSource());
        printDiagnostic("backendSource", diagnostics.getBackendSource());
        printDiagnostic("safeWorkerRef", diagnostics.getSafeWorkerRef());
        printDiagnostic("failureStage", diagnostics.getFailureStage());
        if (hasText(diagnostics.getFailureSummary())) {
            out.println("failureSummary=" + redact(truncate(diagnostics.getFailureSummary(), 500)));
        }
        TaskDiagnostics.CancelCapability cancel = diagnostics.getCancelCapability();
        if (cancel != null) {
            out.println("cancelSupported=" + Boolean.TRUE.equals(cancel.getCancelSupported()));
            printDiagnostic("cancelMode", cancel.getCancelMode());
            out.println("cleanupSupported=" + Boolean.TRUE.equals(cancel.getCleanupSupported()));
            if (cancel.getBackendLimitations() != null && !cancel.getBackendLimitations().isEmpty()) {
                out.println("backendLimitations=" + String.join(",", cancel.getBackendLimitations()));
            }
        }
        TaskDiagnostics.Correlation correlation = diagnostics.getCorrelation();
        if (correlation != null) {
            printDiagnostic("originalTaskId", correlation.getOriginalTaskId());
            printDiagnostic("recoveryCorrelationKey", correlation.getRecoveryCorrelationKey());
            if (correlation.getAttemptNumber() != null) {
                out.println("attemptNumber=" + correlation.getAttemptNumber());
            }
            printDiagnostic("idempotencyKey", correlation.getIdempotencyKey());
        }
    }

    private void printTaskEvidence(TaskEvidence evidence) throws Exception {
        if (evidence == null) {
            return;
        }
        out.println("taskId=" + valueOrEmpty(evidence.getTaskId()));
        out.println("contextId=" + valueOrEmpty(evidence.getContextId()));
        out.println("status=" + valueOrEmpty(evidence.getStatus()));
        out.println("terminal=" + evidence.isTerminal());
        printDiagnostic("terminalStatus", evidence.getTerminalStatus());
        TaskEvidence.FinalAnswer finalAnswer = evidence.getFinalAnswer();
        if (finalAnswer != null) {
            out.println("finalAnswer.available=" + Boolean.TRUE.equals(finalAnswer.getAvailable()));
            printDiagnostic("finalAnswer.source", finalAnswer.getSource());
            printDiagnostic("finalAnswer.messageId", finalAnswer.getMessageId());
            if (hasText(finalAnswer.getSummary())) {
                out.println("finalAnswer.summary=" + redact(truncate(finalAnswer.getSummary(), 500)));
            }
        }
        TaskEvidence.StructuredOutput structuredOutput = evidence.getStructuredOutput();
        if (structuredOutput != null) {
            out.println("structuredOutput.available=" + Boolean.TRUE.equals(structuredOutput.getAvailable()));
            printDiagnostic("structuredOutput.source", structuredOutput.getSource());
            if (structuredOutput.getValue() != null) {
                out.println("structuredOutput.value=" + redact(objectMapper.writeValueAsString(structuredOutput.getValue())));
            }
        }
        if (evidence.getReportRefs() != null) {
            for (TaskEvidence.ReportRef ref : evidence.getReportRefs()) {
                out.println("reportRef"
                        + " type=" + valueOrEmpty(ref != null ? ref.getType() : null)
                        + " ref=" + redact(valueOrEmpty(ref != null ? ref.getRef() : null))
                        + " frameId=" + redact(valueOrEmpty(ref != null ? ref.getFrameId() : null))
                        + " summary=" + redact(valueOrEmpty(ref != null ? ref.getSummary() : null)));
            }
        }
        if (evidence.getArtifactRefs() != null) {
            for (TaskEvidence.ArtifactRef ref : evidence.getArtifactRefs()) {
                out.println("artifactRef"
                        + " path=" + redact(valueOrEmpty(ref != null ? ref.getPath() : null))
                        + " ref=" + redact(valueOrEmpty(ref != null ? ref.getRef() : null))
                        + " hash=" + redact(valueOrEmpty(ref != null ? ref.getHash() : null))
                        + " mtime=" + redact(valueOrEmpty(ref != null ? ref.getMtime() : null))
                        + " summary=" + redact(valueOrEmpty(ref != null ? ref.getSummary() : null)));
            }
        }
    }

    private void printTaskDiagnostics(TaskMessagesPage page, AgentTask task) {
        printDiagnostic("providerTaskId", firstNonBlank(
                page != null ? page.getProviderTaskId() : null,
                task != null ? task.getProviderTaskId() : null));
        printDiagnostic("workerTaskId", firstNonBlank(
                page != null ? page.getWorkerTaskId() : null,
                task != null ? task.getWorkerTaskId() : null));
        Integer lastAckedSeq = page != null && page.getLastAckedSeq() != null
                ? page.getLastAckedSeq()
                : task != null ? task.getLastAckedSeq() : null;
        if (lastAckedSeq != null) {
            out.println("lastAckedSeq=" + lastAckedSeq);
        }
        printDiagnostic("modelConfigId", firstNonBlank(
                page != null ? page.getModelConfigId() : null,
                task != null ? task.getModelConfigId() : null));
        printDiagnostic("modelConfigSource", firstNonBlank(
                page != null ? page.getModelConfigSource() : null,
                task != null ? task.getModelConfigSource() : null));
        printDiagnostic("workerBackend", firstNonBlank(
                page != null ? page.getWorkerBackend() : null,
                task != null ? task.getWorkerBackend() : null));
        printDiagnostic("providerType", firstNonBlank(
                page != null ? page.getProviderType() : null,
                task != null ? task.getProviderType() : null));
        printDiagnostic("taskSource", firstNonBlank(
                page != null ? page.getTaskSource() : null,
                task != null ? task.getTaskSource() : null));
        printDiagnostic("workerSource", firstNonBlank(
                page != null ? page.getWorkerSource() : null,
                task != null ? task.getWorkerSource() : null));
        printDiagnostic("backendSource", firstNonBlank(
                page != null ? page.getBackendSource() : null,
                task != null ? task.getBackendSource() : null));
        if (task != null && task.getEffectiveToolCount() != null) {
            out.println("effectiveToolCount=" + task.getEffectiveToolCount());
        }
        if (task != null && task.getEffectiveFunctionCount() != null) {
            out.println("effectiveFunctionCount=" + task.getEffectiveFunctionCount());
        }
        printDiagnostic("toolScopeSource", task != null ? task.getToolScopeSource() : null);
        printDiagnostic("toolScopeKind", task != null ? task.getToolScopeKind() : null);
        printDiagnostic("functionScopeSource", task != null ? task.getFunctionScopeSource() : null);
        if (task != null && task.getTaskTokenFunctionScopeEmpty() != null) {
            out.println("taskTokenFunctionScopeEmpty=" + task.getTaskTokenFunctionScopeEmpty());
        }
        if (task != null && task.getRuntimeDispatched() != null) {
            out.println("runtimeDispatched=" + task.getRuntimeDispatched());
        }
        printDiagnostic("taskTokenStatus", task != null ? task.getTaskTokenStatus() : null);
        printDiagnostic("failureStage", firstNonBlank(
                page != null ? page.getFailureStage() : null,
                task != null ? task.getFailureStage() : null));
        String failureSummary = firstNonBlank(
                page != null ? page.getFailureSummary() : null,
                task != null ? task.getFailureSummary() : null);
        if (hasText(failureSummary)) {
            out.println("failureSummary=" + redact(truncate(failureSummary, 500)));
        }
    }

    private void printDiagnostic(String key, String value) {
        if (hasText(value)) {
            out.println(key + "=" + redact(value));
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
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

    private String optionalOptionOrConfig(CliArguments args, String option, String key) {
        String value = args.option(option);
        if (hasText(value)) {
            return value;
        }
        return config.get(key);
    }

    private Boolean optionalBooleanOptionOrConfig(CliArguments args, String option, String key) {
        String value = optionalOptionOrConfig(args, option, key);
        if (!hasText(value)) {
            return null;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        throw new UpstreamCliException("--" + option + " must be true or false");
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private Path tenantProfilePath(CliArguments args) {
        String profile = args.option("tenant-profile");
        if (!hasText(profile)) {
            return config.profilePath();
        }
        Path path = Path.of(profile);
        if (!path.isAbsolute()) {
            path = cwd.resolve(path);
        }
        return path.normalize();
    }

    private ProvisionedProfileTargets provisionedProfileTargets(CliArguments args, boolean legacyAlias) {
        String legacyRuntimeProfile = args.option("tenant-profile");
        String runtimeProfile = args.option("tenant-runtime-profile");
        if (hasText(legacyRuntimeProfile) && hasText(runtimeProfile)) {
            throw new UpstreamCliException("Specify only one of --tenant-profile and --tenant-runtime-profile");
        }
        if (!legacyAlias && hasText(legacyRuntimeProfile)) {
            throw new UpstreamCliException("platform tenant ensure does not accept --tenant-profile; use --tenant-runtime-profile");
        }
        Path control = requiredOutputProfile(args.option("platform-control-profile"),
                "platform control profile", "--platform-control-profile");
        Path runtime = requiredOutputProfile(firstNonBlank(runtimeProfile, legacyRuntimeProfile),
                "tenant runtime profile", "--tenant-runtime-profile");
        if (control.equals(runtime)) {
            throw new UpstreamCliException("platform control profile and tenant runtime profile must be different");
        }
        return new ProvisionedProfileTargets(control, runtime);
    }

    private Path platformControlProfilePath(CliArguments args, boolean legacyAlias) {
        String legacyProfile = args.option("tenant-profile");
        String explicitProfile = args.option("platform-control-profile");
        if (hasText(legacyProfile) && hasText(explicitProfile)) {
            throw new UpstreamCliException("Specify only one of --tenant-profile and --platform-control-profile");
        }
        if (!legacyAlias && hasText(legacyProfile)) {
            throw new UpstreamCliException("platform app issue-control-key does not accept --tenant-profile; use --platform-control-profile");
        }
        return requiredOutputProfile(firstNonBlank(explicitProfile, legacyProfile),
                "platform control profile", "--platform-control-profile");
    }

    private Path tenantRuntimeProfilePath(CliArguments args, boolean legacyAlias) {
        String legacyProfile = args.option("tenant-profile");
        String explicitProfile = args.option("tenant-runtime-profile");
        if (hasText(legacyProfile) && hasText(explicitProfile)) {
            throw new UpstreamCliException("Specify only one of --tenant-profile and --tenant-runtime-profile");
        }
        if (!legacyAlias && hasText(legacyProfile)) {
            throw new UpstreamCliException("platform app issue-runtime-key does not accept --tenant-profile; use --tenant-runtime-profile");
        }
        return requiredOutputProfile(firstNonBlank(explicitProfile, legacyProfile),
                "tenant runtime profile", "--tenant-runtime-profile");
    }

    private Path requiredOutputProfile(String value, String description, String option) {
        if (!hasText(value)) {
            throw new UpstreamCliException(description + " is required (" + option + ")");
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = cwd.resolve(path);
        }
        path = path.normalize();
        if (config.profilePath() != null && path.equals(config.profilePath().toAbsolutePath().normalize())) {
            throw new UpstreamCliException(description + " must not overwrite the input management profile");
        }
        config.assertProfileWritable(path);
        return path;
    }

    private static final class ProvisionedProfileTargets {
        private final Path platformControlProfile;
        private final Path tenantRuntimeProfile;

        private ProvisionedProfileTargets(Path platformControlProfile, Path tenantRuntimeProfile) {
            this.platformControlProfile = platformControlProfile;
            this.tenantRuntimeProfile = tenantRuntimeProfile;
        }

        private Path platformControlProfile() {
            return platformControlProfile;
        }

        private Path tenantRuntimeProfile() {
            return tenantRuntimeProfile;
        }
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

    private long parseLong(String value, String description) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new UpstreamCliException("Expected numeric " + description + " but got: " + value);
        }
    }

    private Long parseLongOption(String value, String description) {
        if (!hasText(value)) {
            return null;
        }
        return parseLong(value, description);
    }

    private LocalDateTime parseLocalDateTimeOption(String value, String description) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new UpstreamCliException("Expected ISO-8601 " + description + " but got: " + value);
        }
    }

    private String joinList(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return "(empty)";
        }
        return String.join(",", values);
    }

    private String requiredValue(String value, String message) {
        if (!hasText(value)) {
            throw new UpstreamCliException(message);
        }
        return value;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String powerShellSingleQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static Integer portFromBaseUrl(String baseUrl) {
        if (!hasText(baseUrl)) {
            return null;
        }
        try {
            int port = URI.create(baseUrl).getPort();
            return port >= 0 ? port : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase().contains("win");
    }

    private record ContextLocator(String contextId, String year, String month, String day, String shard,
                                  LocalDate date) {
    }

    private record SessionDirectoryDiagnostics(String contextId,
                                               String taskId,
                                               String providerTaskId,
                                               boolean exists,
                                               String workerBackend,
                                               String physicalWorkerId,
                                               String workerHost,
                                               String hostname,
                                               Path sessionDirectory,
                                               Path logsDirectory,
                                               Path skillToolCallsDirectory,
                                               Path skillToolCallsFile,
                                               Path runtimeMessageEventsDirectory,
                                               Path runtimeMessageEventsFile,
                                               Path llmSubmissionsDirectory,
                                               Path businessMcpDebugLogFile,
                                               String diagnosticMode,
                                               String accessHint,
                                               String notFoundReason) {
    }

    private record InstallerCommand(String role, String releaseBaseUrl, List<String> command, String scriptPreview) {
    }

    private record StartCommand(String role, List<String> command, String scriptPreview) {
    }

    private static final class RuntimeRequestFailure extends RuntimeException {
        private RuntimeRequestFailure(String message) {
            super(message);
        }
    }

    private record WslInstallOptions(String distro, String user) {
    }

    record CommandResult(int exitCode, String output) {
    }

    @FunctionalInterface
    interface CommandRunner {
        CommandResult run(List<String> command, Duration timeout) throws Exception;
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public CommandResult run(List<String> command, Duration timeout) throws Exception {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread outputReader = new Thread(() -> drain(process.getInputStream(), output),
                    "navi-upstream-installer-output");
            outputReader.setDaemon(true);
            outputReader.start();
            try {
                boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    throw new UpstreamCliException("installer timed out after " + timeout.toSeconds() + " seconds");
                }
                outputReader.join(TimeUnit.SECONDS.toMillis(5));
                return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new UpstreamCliException("installer interrupted", e);
            }
        }

        private static void drain(InputStream input, ByteArrayOutputStream output) {
            try (InputStream in = input) {
                in.transferTo(output);
            } catch (IOException ignored) {
                // Best-effort process output capture.
            }
        }
    }

    private static class WorkerHostPlan {
        private final String workerHostId;
        private final String hostUrl;
        private final String install;
        private final String wslDistro;
        private final String wslUser;
        private final WorkerRolePlan claudeCode;
        private final WorkerRolePlan codex;
        private final WorkerRolePlan biz;

        private WorkerHostPlan(String workerHostId,
                               String hostUrl,
                               String install,
                               String wslDistro,
                               String wslUser,
                               WorkerRolePlan claudeCode,
                               WorkerRolePlan codex,
                               WorkerRolePlan biz) {
            this.workerHostId = workerHostId;
            this.hostUrl = hostUrl;
            this.install = install;
            this.wslDistro = wslDistro;
            this.wslUser = wslUser;
            this.claudeCode = claudeCode;
            this.codex = codex;
            this.biz = biz;
        }
    }

    private static class WorkerRolePlan {
        private final String workerId;
        private final String baseUrl;
        private final WorkerHostManifest.WorkerSpec spec;

        private WorkerRolePlan(String workerId, String baseUrl, WorkerHostManifest.WorkerSpec spec) {
            this.workerId = workerId;
            this.baseUrl = baseUrl;
            this.spec = spec;
        }
    }

    private static boolean isSensitiveKey(String key) {
        return key.endsWith("_SECRET") || key.endsWith("_TOKEN")
                || key.endsWith("_API_KEY") || key.endsWith("_KEY");
    }

    private static String valueOrEmpty(Object value) {
        return value == null ? "(empty)" : String.valueOf(value);
    }

    private static String valueOrNull(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static String valueOrUnknown(Object value) {
        return value == null ? "UNKNOWN" : String.valueOf(value);
    }

    private static String booleanOrUnknown(Boolean value) {
        return value == null ? "UNKNOWN" : value.toString();
    }

    private static String firstText(String first, String second) {
        return hasText(first) ? first : second;
    }

    private static String emptyIfNull(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
