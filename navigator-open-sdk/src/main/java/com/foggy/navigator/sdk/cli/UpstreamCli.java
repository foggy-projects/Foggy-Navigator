package com.foggy.navigator.sdk.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.foggy.navigator.sdk.api.AgentApi;
import com.foggy.navigator.sdk.api.BusinessAgentApi;
import com.foggy.navigator.sdk.api.DirectoryApi;
import com.foggy.navigator.sdk.api.WorkerApi;
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
import com.foggy.navigator.sdk.model.businessagent.ImportBusinessFunctionManifestForm;
import com.foggy.navigator.sdk.model.businessagent.IssueControlCredentialForm;
import com.foggy.navigator.sdk.model.businessagent.IssueRuntimeCredentialForm;
import com.foggy.navigator.sdk.model.businessagent.IssuedCredentialDTO;
import com.foggy.navigator.sdk.model.businessagent.LlmModelConfigDTO;
import com.foggy.navigator.sdk.model.businessagent.RotateModelConfigKeyForm;
import com.foggy.navigator.sdk.model.businessagent.RotateUpstreamAdminCredentialForm;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class UpstreamCli {
    private static final String CREDENTIALS_NOT_REPLAYABLE = "CREDENTIALS_NOT_REPLAYABLE";
    private static final String CLAUDE_WORKER_INSTALL_BASE_URL =
            "https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker";
    private static final String CODEX_WORKER_INSTALL_BASE_URL =
            "https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker";
    private static final String BIZ_WORKER_INSTALL_BASE_URL =
            "https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/langgraph-biz-worker";

    private final PrintStream out;
    private final PrintStream err;
    private final Path cwd;
    private final ObjectMapper objectMapper;
    private final CommandRunner commandRunner;
    private UpstreamCliConfig config;
    private String resolvedClientAppAccessToken;
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

    private int dispatch(CliArguments args) throws Exception {
        return switch (args.command()) {
            case "config check" -> configCheck();
            case "runtime-token" -> runtimeToken(args);
            case "owner-smoke" -> ownerSmoke(args);
            case "verify-agent-readiness", "verify-agent-grant" -> verifyAgentReadiness(args);
            case "inspect", "inspect runtime" -> inspectRuntime(args);
            case "ensure-grant" -> ensureGrant(args);
            case "ask" -> ask(args);
            case "messages" -> messages(args);
            case "sessions" -> sessions(args);
            case "session-messages" -> sessionMessages(args);
            case "skill tree" -> skillTree(args);
            case "skill read" -> skillRead(args);
            case "skill sync" -> skillSync(args);
            case "skill clear-public" -> skillClearPublic(args);
            case "skill clear-account" -> skillClearAccount(args);
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
            case "model grants" -> modelGrants(args);
            case "model grant" -> modelGrant(args);
            case "model set-default" -> modelSetDefault(args);
            case "model create" -> modelCreate(args);
            case "model update" -> modelUpdate(args);
            case "model rotate-key" -> modelRotateKey(args);
            case "model system-list" -> modelSystemList(args);
            case "model system-create" -> modelSystemCreate(args);
            case "model system-update" -> modelSystemUpdate(args);
            case "model system-rotate-key" -> modelSystemRotateKey(args);
            case "admin-key", "admin-key help" -> adminKeyUsage();
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
            case "client-app ensure-tenant" -> upstreamTenantClientAppEnsure(args);
            case "client-app issue-control-key" -> upstreamClientAppIssueControlKey(args);
            case "client-app issue-runtime-key", "client-app issue-runtime-credential" ->
                    upstreamClientAppIssueRuntimeKey(args);
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

    private int usage() {
        out.println("Usage: navi upstream <command> [options]");
        out.println("Commands: config check, runtime-token, owner-smoke, inspect runtime, verify-agent-readiness, verify-agent-grant, ensure-grant, ask, messages, sessions, session-messages, skill tree, skill read, skill sync, skill clear-public, skill clear-account, agent sync, agent model-bindings/bind-model/unbind-model/set-default-model, agent workspace-bindings/bind-workspace/unbind-workspace/set-default-workspace, agent worker-bindings/bind-worker/unbind-worker/set-default-worker, agent system-list/system-create/system-get/system-update, agent system-model-bindings/system-bind-model/system-unbind-model/system-set-default-model, agent system-workspace-bindings/system-bind-workspace/system-unbind-workspace/system-set-default-workspace, agent system-worker-bindings/system-bind-worker/system-unbind-worker/system-set-default-worker, function import, function grant, function grant-status, function visible, route list, route set, route status, model grants, model grant, model set-default, model create, model update, model rotate-key, model system-list/system-create/system-update/system-rotate-key, admin-key request, admin-key status, admin-key claim, admin-key list, admin-key approve, admin-key deny, admin-key revoke, admin-key rotate, client-app list, client-app ensure, client-app ensure-tenant, client-app issue-runtime-key, client-app issue-control-key, worker-host apply/update/verify/install, worker list/create/get/update/delete/health/processes/kill, directory list/init/get/delete/env/files/client-list/client-init/client-get/client-delete/client-env/client-files, account-context list, account-context read, account-context write-policy");
        out.println("Internal compatibility: worker-pool list/create/register-worker/add-member/status. Normal upstream bootstrap should use worker-host apply.");
        out.println("  owner-smoke --upstream-user-id <id> [--agent-code <id>] [--model-config-id <id>] [--model-variant <name>] [--directory-id <id>] [--no-directory-required]");
        out.println("  ask --upstream-user-id <id> --message <text> [--context-id <returnedContextId>] [--model-config-id <id>] [--model-variant <name>] [--client-context-json <json>|--client-context-file <path>]");
        out.println("  messages --task-id <taskId> --agent-code <agentId> [--poll] [--interval <seconds>]");
        out.println("    New sessions should omit --context-id; reuse the returned contextId only for continuation. clientContext is metadata, not prompt/model-budget config.");
        out.println("  model create/update uses NAVI_CONTROL_API_KEY and creates ClientApp-owned models.");
        out.println("  model system-create/system-update uses NAVI_ADMIN_API_KEY and creates UpstreamSystem-owned shared models.");
        out.println("  model create/system-create accepts --worker-backend LANGGRAPH_BIZ|OPENAI_CODEX|CLAUDE_CODE|GEMINI_CLI.");
        return 0;
    }

    private int adminKeyUsage() {
        out.println("Usage: navi upstream admin-key <command> [options]");
        out.println("Commands: request, status, claim, list, approve, deny, revoke, rotate");
        out.println("  request --upstream-system-id <id> --requested-tenant-id <tenantId> [--multi-tenant] --write-profile");
        out.println("  status  [--request-code <code>]");
        out.println("  claim   [--request-code <code>] [--claim-token-env <env>] --write-profile");
        out.println("  approve --request-code <code> --authorized-tenant-ids <tenantId[,tenantId]> [--namespace <prefix>] [--scopes <scope[,scope]>] [--credential-expires-at <yyyy-MM-ddTHH:mm:ss>]");
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
        out.println("  ensure-tenant --source-system <system> --source-tenant-id <id> [--name <name>] [--tenant-profile <path>] [--rotate-credentials] --write-profile");
        out.println("  issue-runtime-key --client-app-id <id> [--tenant-profile <path>] [--rotate-runtime-credential] --write-profile");
        out.println("  issue-control-key --client-app-id <id> [--scopes <scope[,scope]>] [--tenant-profile <path>] --write-profile");
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
        out.println("Codex is Navi-routed through claudeCode.codexConfig; workers.codex.workerId/direct OPENAI_CODEX identity is not supported yet.");
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
        out.println("Internal compatibility commands. Normal upstream bootstrap should use worker + directory + model + agent; WorkerPool is a Navigator routing artifact.");
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

    private int upstreamTenantClientAppEnsure(CliArguments args) {
        if (!args.flag("write-profile")) {
            throw new UpstreamCliException("client-app ensure-tenant requires --write-profile to store one-time credentials without printing them");
        }
        Path targetProfile = tenantProfilePath(args);
        config.assertProfileWritable(targetProfile);

        String sourceSystem = requiredOptionOrConfig(args, "source-system", "NAVI_UPSTREAM_SYSTEM_ID", "source system");
        String sourceTenantId = sourceTenantId(args);
        EnsureUpstreamTenantClientAppForm form = new EnsureUpstreamTenantClientAppForm();
        form.setSourceSystem(sourceSystem);
        form.setSourceTenantId(sourceTenantId);
        form.setClientAppName(args.option("name"));
        form.setCapabilityDomain(args.option("capability-domain"));
        form.setTenantName(args.option("tenant-name"));
        form.setAgentRole(args.option("agent-role"));
        form.setAgentBundleCode(args.option("agent-bundle-code"));
        form.setModelProfileCode(optionalOptionOrConfig(args, "model-profile-code", "NAVI_MODEL_PROFILE_CODE"));
        form.setModelConfigId(optionalOptionOrConfig(args, "model-config-id", "NAVI_MODEL_CONFIG_ID"));
        form.setSkillId(optionalOptionOrConfig(args, "skill-id", "NAVI_SKILL_ID"));
        form.setWorkerPoolId(optionalOptionOrConfig(args, "worker-pool-id", "NAVI_WORKER_POOL_ID"));
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

        writeProvisionedTenantProfile(targetProfile, dto, sourceSystem, sourceTenantId);

        out.println("client-app ensure-tenant ok");
        out.println("profileUpdated=" + targetProfile);
        out.println("stored=" + provisionedTenantStoredKeys(dto));
        printUpstreamTenantClientAppProvisioning(dto);
        return 0;
    }

    private int upstreamClientAppIssueControlKey(CliArguments args) {
        if (!args.flag("write-profile")) {
            throw new UpstreamCliException("client-app issue-control-key requires --write-profile to store NAVI_CONTROL_API_KEY without printing it");
        }
        Path targetProfile = tenantProfilePath(args);
        config.assertProfileWritable(targetProfile);

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

        config.writeProfileValue(targetProfile, "NAVI_BASE_URL", config.required("NAVI_BASE_URL", "Navigator base URL"));
        config.writeProfileValue(targetProfile, "NAVI_TENANT_ID", emptyIfNull(credential.getTenantId()));
        config.writeProfileValue(targetProfile, "NAVI_CLIENT_APP_ID", emptyIfNull(credential.getClientAppId()));
        config.writeProfileValue(targetProfile, "NAVI_CONTROL_API_KEY", credential.getControlApiKey());

        out.println("client-app issue-control-key ok");
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

    private int upstreamClientAppIssueRuntimeKey(CliArguments args) {
        if (!args.flag("write-profile")) {
            throw new UpstreamCliException("client-app issue-runtime-key requires --write-profile to store NAVI_CLIENT_APP_KEY and NAVI_CLIENT_APP_SECRET without printing them");
        }
        Path targetProfile = tenantProfilePath(args);
        config.assertProfileWritable(targetProfile);

        IssueRuntimeCredentialForm form = new IssueRuntimeCredentialForm();
        form.setDescription(args.option("description"));
        form.setExpiresAt(parseLocalDateTimeOption(args.option("expires-at"), "expires at"));

        String clientAppId = requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id");
        IssuedCredentialDTO credential = upstreamAdminApi()
                .issueUpstreamClientAppRuntimeCredential(clientAppId, form);
        if (credential == null || !hasText(credential.getAppKey()) || !hasText(credential.getSecret())) {
            throw new UpstreamCliException("client-app issue-runtime-key response did not include NAVI_CLIENT_APP_KEY and NAVI_CLIENT_APP_SECRET");
        }

        config.writeProfileValue(targetProfile, "NAVI_BASE_URL", config.required("NAVI_BASE_URL", "Navigator base URL"));
        config.writeProfileValue(targetProfile, "NAVI_TENANT_ID", emptyIfNull(credential.getTenantId()));
        config.writeProfileValue(targetProfile, "NAVI_CLIENT_APP_ID", emptyIfNull(credential.getClientAppId()));
        config.writeProfileValue(targetProfile, "NAVI_CLIENT_APP_KEY", credential.getAppKey());
        config.writeProfileValue(targetProfile, "NAVI_CLIENT_APP_SECRET", credential.getSecret());
        config.writeProfileValue(targetProfile, "NAVI_CLIENT_APP_ACCESS_TOKEN", "");

        out.println("client-app issue-runtime-key ok");
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
        if (args.flag("write-profile")) {
            config.assertProfileWritable();
        }
        Map<String, Object> worker = upstreamAdminApi().registerUpstreamWorkerIdentity(
                readJsonMap(requiredOption(args, "file", "biz worker identity json file")));
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
        return missing;
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
                        + (hasText(check.getMessage()) ? " message=" + redact(check.getMessage()) : ""));
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
        AgentTask task = agentApi().askWithClientAppAccessToken(
                agent,
                message,
                args.option("context-id"),
                parseInteger(args.option("max-turns")),
                clientContext,
                modelConfigId(args),
                modelVariant(args),
                null,
                clientAppKey(args),
                clientAppAccessToken(args),
                upstreamUserId);
        printTask(task);
        return 0;
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

    private ClientAppModelConfigForm buildModelConfigForm(CliArguments args, boolean create) {
        ClientAppModelConfigForm form = new ClientAppModelConfigForm();
        form.setName(create ? requiredOption(args, "name", "model name") : args.option("name"));
        form.setBaseUrl(create ? requiredOption(args, "model-base-url", "LLM model base URL") : args.option("model-base-url"));
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
        form.setWorkerBackend(args.option("worker-backend"));
        if (create) {
            form.setApiKey(config.required("NAVI_LLM_API_KEY", "LLM API key; pass --api-key-env <envName>"));
        }
        return form;
    }

    private void printLlmModelConfig(String prefix, LlmModelConfigDTO model) {
        if (model == null) {
            return;
        }
        out.println(prefix + ".id=" + valueOrEmpty(model.getId()));
        out.println(prefix + ".tenantId=" + valueOrEmpty(model.getTenantId()));
        out.println(prefix + ".name=" + valueOrEmpty(model.getName()));
        out.println(prefix + ".category=" + valueOrEmpty(model.getCategory()));
        out.println(prefix + ".modelName=" + valueOrEmpty(model.getModelName()));
        out.println(prefix + ".workerBackend=" + valueOrEmpty(model.getWorkerBackend()));
        out.println(prefix + ".ownerType=" + valueOrEmpty(model.getOwnerType()));
        out.println(prefix + ".ownerId=" + valueOrEmpty(model.getOwnerId()));
        out.println(prefix + ".enabled=" + valueOrEmpty(model.getEnabled()));
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
        String adminApiKey = config.get("NAVI_ADMIN_API_KEY");
        if (!hasText(adminApiKey)) {
            throw new UpstreamCliException("upstream admin credential is required (NAVI_ADMIN_API_KEY)");
        }
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
        String controlApiKey = config.get("NAVI_CONTROL_API_KEY");
        if (!hasText(controlApiKey)) {
            throw new UpstreamCliException("client app control credential is required (NAVI_CONTROL_API_KEY)");
        }
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
        String adminApiKey = config.get("NAVI_ADMIN_API_KEY");
        if (!hasText(adminApiKey)) {
            throw new UpstreamCliException("upstream admin credential is required (NAVI_ADMIN_API_KEY)");
        }
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
        String controlApiKey = config.get("NAVI_CONTROL_API_KEY");
        if (!hasText(controlApiKey)) {
            throw new UpstreamCliException("client app control credential is required (NAVI_CONTROL_API_KEY)");
        }
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
                + " upstreamSystemId=" + valueOrEmpty(credential != null ? credential.getUpstreamSystemId() : null)
                + " status=" + valueOrEmpty(credential != null ? credential.getStatus() : null));
        if (credential == null) {
            return;
        }
        out.println(prefix + " authorizedTenantIds=" + joinList(credential.getAuthorizedTenantIds()));
        out.println(prefix + " authorizedClientAppNamespace=" + valueOrEmpty(credential.getAuthorizedClientAppNamespace()));
        out.println(prefix + " scopes=" + joinList(credential.getScopes()));
        out.println(prefix + " expiresAt=" + valueOrEmpty(credential.getExpiresAt()));
        out.println(prefix + " revokedAt=" + valueOrEmpty(credential.getRevokedAt()));
        out.println(prefix + " lastUsedAt=" + valueOrEmpty(credential.getLastUsedAt()));
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

    private void writeProvisionedTenantProfile(Path targetProfile,
                                               UpstreamTenantClientAppProvisioningDTO dto,
                                               String sourceSystem,
                                               String sourceTenantId) {
        config.writeProfileValue(targetProfile, "NAVI_BASE_URL", config.required("NAVI_BASE_URL", "Navigator base URL"));
        config.writeProfileValue(targetProfile, "NAVI_TENANT_ID", emptyIfNull(dto.getNavigatorTenantId()));
        config.writeProfileValue(targetProfile, "NAVI_CLIENT_APP_ID", emptyIfNull(dto.getClientAppId()));
        config.writeProfileValue(targetProfile, "NAVI_UPSTREAM_SYSTEM_ID", emptyIfNull(sourceSystem));
        config.writeProfileValue(targetProfile, "NAVI_SOURCE_TENANT_ID", emptyIfNull(sourceTenantId));
        config.writeProfileValue(targetProfile, "NAVI_UPSTREAM_REF", emptyIfNull(sourceTenantId));
        config.writeProfileValue(targetProfile, "NAVI_AGENT_CODE", emptyIfNull(dto.getRootAgentId()));
        config.writeProfileValue(targetProfile, "NAVI_MODEL_CONFIG_ID", emptyIfNull(dto.getModelConfigId()));
        config.writeProfileValue(targetProfile, "NAVI_SKILL_ID", emptyIfNull(dto.getSkillId()));
        config.writeProfileValue(targetProfile, "NAVI_WORKER_POOL_ID", emptyIfNull(dto.getWorkerPoolId()));
        if (hasText(dto.getClientAppKey())) {
            config.writeProfileValue(targetProfile, "NAVI_CLIENT_APP_KEY", dto.getClientAppKey());
        }
        if (hasText(dto.getClientAppSecret())) {
            config.writeProfileValue(targetProfile, "NAVI_CLIENT_APP_SECRET", dto.getClientAppSecret());
        }
        if (hasText(dto.getControlApiKey())) {
            config.writeProfileValue(targetProfile, "NAVI_CONTROL_API_KEY", dto.getControlApiKey());
        }
    }

    private String provisionedTenantStoredKeys(UpstreamTenantClientAppProvisioningDTO dto) {
        List<String> keys = new ArrayList<>(List.of(
                "NAVI_BASE_URL",
                "NAVI_TENANT_ID",
                "NAVI_CLIENT_APP_ID",
                "NAVI_UPSTREAM_SYSTEM_ID",
                "NAVI_SOURCE_TENANT_ID",
                "NAVI_UPSTREAM_REF",
                "NAVI_AGENT_CODE",
                "NAVI_MODEL_CONFIG_ID",
                "NAVI_SKILL_ID",
                "NAVI_WORKER_POOL_ID"));
        if (hasText(dto.getClientAppKey())) {
            keys.add("NAVI_CLIENT_APP_KEY");
        }
        if (hasText(dto.getClientAppSecret())) {
            keys.add("NAVI_CLIENT_APP_SECRET");
        }
        if (hasText(dto.getControlApiKey())) {
            keys.add("NAVI_CONTROL_API_KEY");
        }
        return String.join(",", keys);
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
        out.println("clientAppKey=" + SecretMasker.mask(dto != null ? dto.getClientAppKey() : null));
        out.println("clientAppSecret=" + SecretMasker.mask(dto != null ? dto.getClientAppSecret() : null));
        out.println("controlApiKey=" + SecretMasker.mask(dto != null ? dto.getControlApiKey() : null));
        out.println("rootAgentId=" + valueOrEmpty(dto != null ? dto.getRootAgentId() : null));
        out.println("modelConfigId=" + valueOrEmpty(dto != null ? dto.getModelConfigId() : null));
        out.println("skillId=" + valueOrEmpty(dto != null ? dto.getSkillId() : null));
        out.println("workerPoolId=" + valueOrEmpty(dto != null ? dto.getWorkerPoolId() : null));
        out.println("bindingVersion=" + valueOrEmpty(dto != null ? dto.getBindingVersion() : null));
        out.println("status=" + valueOrEmpty(dto != null ? dto.getStatus() : null));
        out.println("errorCode=" + valueOrEmpty(dto != null ? dto.getErrorCode() : null));
        out.println("credentialsReplayable=" + (dto != null && Boolean.TRUE.equals(dto.getCredentialsReplayable())));
        if (dto != null && hasText(dto.getMessage())) {
            out.println("message=" + redact(dto.getMessage()));
        }
        out.println("created=" + (dto != null && Boolean.TRUE.equals(dto.getCreated())));
        out.println("rotated=" + (dto != null && Boolean.TRUE.equals(dto.getRotated())));
        if (dto != null && dto.getBlockers() != null) {
            for (String blocker : dto.getBlockers()) {
                out.println("blocker=" + redact(blocker));
            }
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
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        out.println(redact(json));
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
                    + " content=" + redact(truncate(message.getContent(), 500)));
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

    private record InstallerCommand(String role, String releaseBaseUrl, List<String> command, String scriptPreview) {
    }

    private record StartCommand(String role, List<String> command, String scriptPreview) {
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

    private static String firstText(String first, String second) {
        return hasText(first) ? first : second;
    }

    private static String emptyIfNull(Object value) {
        return value == null ? "" : String.valueOf(value);
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
