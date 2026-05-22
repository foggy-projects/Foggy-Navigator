package com.foggy.navigator.sdk.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.sdk.api.AgentApi;
import com.foggy.navigator.sdk.api.BusinessAgentApi;
import com.foggy.navigator.sdk.api.DirectoryApi;
import com.foggy.navigator.sdk.api.WorkerApi;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.AgentTask;
import com.foggy.navigator.sdk.model.AgentReadiness;
import com.foggy.navigator.sdk.model.AgentReadinessCheck;
import com.foggy.navigator.sdk.model.Directory;
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
import com.foggy.navigator.sdk.model.businessagent.ApproveUpstreamBootstrapRequestForm;
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
import com.foggy.navigator.sdk.model.businessagent.IssuedCredentialDTO;
import com.foggy.navigator.sdk.model.businessagent.RotateModelConfigKeyForm;
import com.foggy.navigator.sdk.model.businessagent.RotateUpstreamAdminCredentialForm;
import com.foggy.navigator.sdk.model.businessagent.SkillClearResultDTO;
import com.foggy.navigator.sdk.model.businessagent.SkillBundleDTO;
import com.foggy.navigator.sdk.model.businessagent.SyncAccountSkillBundleForm;
import com.foggy.navigator.sdk.model.businessagent.SyncBusinessAgentBundleForm;
import com.foggy.navigator.sdk.model.businessagent.SyncSkillBundleForm;
import com.foggy.navigator.sdk.model.businessagent.UpstreamAdminCredentialClaimDTO;
import com.foggy.navigator.sdk.model.businessagent.UpstreamAdminCredentialDTO;
import com.foggy.navigator.sdk.model.businessagent.UpstreamTenantClientAppProvisioningDTO;
import com.foggy.navigator.sdk.model.businessagent.UpstreamBootstrapRequestCreatedDTO;
import com.foggy.navigator.sdk.model.businessagent.UpstreamBootstrapRequestDTO;
import com.foggy.navigator.sdk.model.businessagent.UpsertClientAppUpstreamRouteForm;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class UpstreamCli {
    private static final String CREDENTIALS_NOT_REPLAYABLE = "CREDENTIALS_NOT_REPLAYABLE";

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
            case "skill clear-public" -> skillClearPublic(args);
            case "skill clear-account" -> skillClearAccount(args);
            case "agent sync" -> agentSync(args);
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
            case "worker", "worker help" -> workerUsage();
            case "worker list" -> workerList(args);
            case "worker create" -> workerCreate(args);
            case "worker get" -> workerGet(args);
            case "worker update" -> workerUpdate(args);
            case "worker delete" -> workerDelete(args);
            case "worker health" -> workerHealth(args);
            case "worker processes" -> workerProcesses(args);
            case "worker kill" -> workerKill(args);
            case "directory", "directory help" -> directoryUsage();
            case "directory list" -> directoryList(args);
            case "directory init" -> directoryInit(args);
            case "directory get" -> directoryGet(args);
            case "directory delete" -> directoryDelete(args);
            case "directory env" -> directoryEnv(args);
            case "directory files" -> directoryFiles(args);
            case "worker-pool", "worker-pool help" -> workerPoolUsage();
            case "worker-pool list" -> workerPoolList(args);
            case "worker-pool create" -> workerPoolCreate(args);
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
        out.println("Commands: config check, runtime-token, verify-agent-readiness, verify-agent-grant, ensure-grant, ask, messages, sessions, session-messages, skill tree, skill read, skill sync, skill clear-public, skill clear-account, agent sync, function import, function grant, function grant-status, function visible, route list, route set, route status, model grants, model grant, model set-default, model create, model update, model rotate-key, admin-key request, admin-key status, admin-key claim, admin-key list, admin-key approve, admin-key deny, admin-key revoke, admin-key rotate, client-app list, client-app ensure, client-app ensure-tenant, client-app issue-control-key, worker list/create/get/update/delete/health/processes/kill, directory list/init/get/delete/env/files, worker-pool list/create/add-member/status, account-context list, account-context read, account-context write-policy");
        out.println("  ask --upstream-user-id <id> --message <text> [--context-id <returnedContextId>] [--client-context-json <json>|--client-context-file <path>]");
        out.println("    New sessions should omit --context-id; reuse the returned contextId only for continuation. clientContext is metadata, not prompt/model-budget config.");
        out.println("  model create/update supports --runtime-budget-preset <key> and optional --runtime-budget-override-json <json> for LangGraph Biz.");
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
        out.println("Commands: list, ensure, ensure-tenant, issue-control-key");
        out.println("  list [--target-tenant-id <tenantId>]");
        out.println("  ensure --target-tenant-id <tenantId> --upstream-ref <ref> [--name <name>] [--tenant-profile <path>] [--write-profile]");
        out.println("  ensure-tenant --source-system <system> --source-tenant-id <id> [--name <name>] [--tenant-profile <path>] [--rotate-credentials] --write-profile");
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

    private int directoryUsage() {
        out.println("Usage: navi upstream directory <command> [options]");
        out.println("Commands: list, init, get, delete, env, files");
        out.println("  list [--target-tenant-id <tenantId>] [--worker-id <id>]");
        out.println("  init --file <json> [--write-profile]");
        out.println("  get|delete --directory-id <id>");
        out.println("  env|files --directory-id <id> --file <json>");
        return 0;
    }

    private int workerPoolUsage() {
        out.println("Usage: navi upstream worker-pool <command> [options]");
        out.println("Commands: list, create, add-member, status");
        out.println("  list [--target-tenant-id <tenantId>]");
        out.println("  create --file <json> [--target-tenant-id <tenantId>] [--write-profile]");
        out.println("  add-member --pool-id <id> --worker-id <workerId> [--target-tenant-id <tenantId>]");
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

    private int workerPoolAddMember(CliArguments args) {
        upstreamAdminApi().addUpstreamWorkerPoolMember(
                requiredOptionOrConfig(args, "pool-id", "NAVI_WORKER_POOL_ID", "worker pool id"),
                Map.of("workerId", requiredOptionOrConfig(args, "worker-id", "NAVI_WORKER_ID", "worker id")),
                optionalOptionOrConfig(args, "target-tenant-id", "NAVI_TARGET_TENANT_ID"));
        out.println("worker-pool add-member ok");
        return 0;
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
        String agent = agentCode(args);
        String upstreamUserId = upstreamUserId(args);
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
                clientAppKey(args),
                clientAppAccessToken(args),
                upstreamUserId);
        printTask(task);
        return 0;
    }

    private int messages(CliArguments args) throws InterruptedException {
        String agent = agentCode(args);
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
        ObjectMapper mapper = new ObjectMapper();
        SkillBundleDTO dto;
        String normalizedScope = normalizeSkillBundleScope(scope);
        if ("ACCOUNT_PRIVATE".equals(normalizedScope)) {
            SyncAccountSkillBundleForm form = mapper.readValue(json, SyncAccountSkillBundleForm.class);
            String upstreamUserId = upstreamUserId(args);
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
        SyncBusinessAgentBundleForm form = new ObjectMapper().readValue(json, SyncBusinessAgentBundleForm.class);
        if (!hasText(form.getClientAppId())) {
            form.setClientAppId(requiredOptionOrConfig(args, "client-app-id", "NAVI_CLIENT_APP_ID", "client app id"));
        }

        BusinessAgentBundleDTO dto = businessAgentControlApi().syncBusinessAgentBundle(form);
        printBusinessAgentBundle(dto);
        return 0;
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
        if (create) {
            form.setApiKey(config.required("NAVI_LLM_API_KEY", "LLM API key; pass --api-key-env <envName>"));
        }
        return form;
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
        String adminToken = config.get("NAVI_ADMIN_TOKEN");
        String upstreamAdminApiKey = config.get("NAVI_ADMIN_API_KEY");
        if (!hasText(controlApiKey) && !hasText(adminToken) && !hasText(upstreamAdminApiKey)) {
            throw new UpstreamCliException("control-plane credential is required (NAVI_CONTROL_API_KEY; admin fallback: NAVI_ADMIN_TOKEN or NAVI_ADMIN_API_KEY)");
        }
        return new BusinessAgentApi(new HttpHelper(
                config.required("NAVI_BASE_URL", "Navigator base URL"),
                null,
                adminToken,
                config.get("NAVI_TENANT_ID"),
                controlApiKey,
                null,
                upstreamAdminApiKey,
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

    private void printBusinessAgentBundle(BusinessAgentBundleDTO dto) {
        out.println("agent sync ok");
        out.println("clientAppId=" + valueOrEmpty(dto != null ? dto.getClientAppId() : null));
        out.println("agentId=" + valueOrEmpty(dto != null ? dto.getAgentId() : null));
        out.println("skillId=" + valueOrEmpty(dto != null ? dto.getSkillId() : null));
        out.println("workerId=" + valueOrEmpty(dto != null ? dto.getWorkerId() : null));
        out.println("defaultModelConfigId=" + valueOrEmpty(dto != null ? dto.getDefaultModelConfigId() : null));
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
                + " backend=" + valueOrEmpty(grant != null ? grant.getWorkerBackend() : null)
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
        return new ObjectMapper().readValue(Files.readString(path, StandardCharsets.UTF_8), type);
    }

    private Map<String, Object> readJsonMap(String file) throws Exception {
        Path path = cwd.resolve(file).normalize();
        if (!Files.isRegularFile(path)) {
            throw new UpstreamCliException("json file not found: " + path);
        }
        return new ObjectMapper().readValue(Files.readString(path, StandardCharsets.UTF_8), new TypeReference<>() {});
    }

    private Map<String, String> readJsonStringMap(String file) throws Exception {
        Path path = cwd.resolve(file).normalize();
        if (!Files.isRegularFile(path)) {
            throw new UpstreamCliException("json file not found: " + path);
        }
        return new ObjectMapper().readValue(Files.readString(path, StandardCharsets.UTF_8), new TypeReference<>() {});
    }

    private void printJson(Object value) throws Exception {
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(value);
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

    private static boolean isSensitiveKey(String key) {
        return key.endsWith("_SECRET") || key.endsWith("_TOKEN")
                || key.endsWith("_API_KEY") || key.endsWith("_KEY");
    }

    private static String valueOrEmpty(Object value) {
        return value == null ? "(empty)" : String.valueOf(value);
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
