package com.foggy.navigator.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.auth.repository.UserRepository;
import com.foggy.navigator.auth.util.PasswordUtil;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.codex.worker.model.dto.CodexAppServerEndpointSyncDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeDTO;
import com.foggy.navigator.codex.worker.model.form.CodexAppServerEndpointForm;
import com.foggy.navigator.codex.worker.model.form.CodexRuntimeRoutingForm;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.codex.worker.service.CodexAppServerEndpointService;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRegistryService;
import com.foggy.navigator.common.entity.LlmModelConfigEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.UserEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ModelAccessScope;
import com.foggy.navigator.common.enums.UserRole;
import com.foggy.navigator.common.enums.UserStatus;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.metadata.query.config.repository.LlmModelConfigRepository;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in, real-process regression for the Navigator -> Java -> app-server Worker path.
 * The shell runner owns the isolated Worker and mock Responses API processes; this test
 * starts the real Navigator web application on H2 and exercises only public task APIs.
 */
@SpringBootTest(
        classes = FogyNavigatorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.lazy-initialization=true",
                "spring.datasource.url=jdbc:h2:mem:bug007-navigator-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "navigator.database.startup-migrations.enabled=false",
                "navigator.codex.runtime.expected-cli-version=0.144.3",
                "system.root.password=bug007-unused-root-password"
        }
)
@EnabledIfSystemProperty(named = "bug007.e2e.enabled", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodexAppServerNavigatorE2ETest {

    private static final String TENANT_ID = "bug007-tenant";
    private static final String USER_ID = "bug007-user";
    private static final String USERNAME = "bug007-e2e";
    private static final String PASSWORD = "bug007-e2e-password";
    private static final String DIRECTORY_ID = "bug007-directory";
    private static final String MODEL_ID = "bug007-model";
    private static final String MISMATCH_MODEL_ID = "bug007-model-mismatch";
    private static final String MODEL = "gpt-5.6-terra";
    private static final String PROVIDER = "codex-app-server-worker";
    private static final String CWD = "/home/sa/workspace/Foggy-Navigator";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TASK_TIMEOUT = Duration.ofSeconds(45);

    @LocalServerPort
    private int navigatorPort;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordUtil passwordUtil;
    @Autowired private ClaudeWorkerRepository workerRepository;
    @Autowired private WorkingDirectoryRepository directoryRepository;
    @Autowired private LlmModelConfigRepository modelRepository;
    @Autowired private SessionTaskRepository sessionTaskRepository;
    @Autowired private SessionMessageRepository sessionMessageRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private CodexTaskRepository codexTaskRepository;
    @Autowired private CredentialEncryptor credentialEncryptor;
    @Autowired private CodexAppServerEndpointService endpointService;
    @Autowired private CodexRuntimeRegistryService runtimeRegistryService;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private String workerBaseUrl;
    private String mockBaseUrl;
    private String workerId;
    private String workerToken;
    private String authToken;
    private CodexRuntimeDTO runtime;

    @BeforeAll
    void setUpRealRuntimeBinding() throws Exception {
        workerBaseUrl = requiredProperty("bug007.worker.base-url");
        mockBaseUrl = requiredProperty("bug007.mock.base-url");
        workerId = requiredProperty("bug007.worker.id");
        workerToken = System.getProperty("bug007.worker.token", "");

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setTenantId(TENANT_ID);
        user.setUsername(USERNAME);
        user.setPasswordHash(passwordUtil.encode(PASSWORD));
        user.setEmail("bug007-e2e@foggy.local");
        user.setDisplayName("BUG-007 E2E");
        user.setRoles(UserRole.TENANT_ADMIN.name());
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId(workerId);
        worker.setUserId(USER_ID);
        worker.setTenantId(TENANT_ID);
        worker.setName("BUG-007 isolated app-server Worker");
        worker.setBaseUrl(workerBaseUrl);
        worker.setAuthToken(credentialEncryptor.encrypt(workerToken));
        worker.setAuthMode("API_KEY");
        worker.setStatus("ONLINE");
        workerRepository.save(worker);

        WorkingDirectoryEntity directory = new WorkingDirectoryEntity();
        directory.setDirectoryId(DIRECTORY_ID);
        directory.setWorkerId(workerId);
        directory.setUserId(USER_ID);
        directory.setTenantId(TENANT_ID);
        directory.setProjectName("Foggy Navigator BUG-007 E2E");
        directory.setPath(CWD);
        directory.setDirectoryType("STANDARD");
        directory.setWorktree(false);
        directoryRepository.save(directory);

        saveModel(MODEL_ID, "mock-key");
        saveModel(MISMATCH_MODEL_ID, "mock-key-different-lane");

        CodexAppServerEndpointForm endpoint = new CodexAppServerEndpointForm();
        endpoint.setWorkerId(workerId);
        endpoint.setEndpointUrl(workerBaseUrl);
        endpoint.setAuthToken(workerToken);
        String endpointId = endpointService.create(endpoint).getEndpointId();
        CodexAppServerEndpointSyncDTO sync = endpointService.synchronize(endpointId);
        assertThat(sync.getRuntime()).as("endpoint capability sync").isNotNull();
        assertThat(sync.getRuntime().getReadinessStatus()).isEqualTo("READY");
        runtime = promoteToAllDefault(sync.getRuntime());

        authToken = login();
    }

    @Test
    void navigatorApiPreservesSingleChildThreadIsolationAndLaneContinuity() throws Exception {
        rateLimitProbeDoesNotCreateChildBeforeFirstTask();
        differentThreadsShareOneChildWithoutCrossTalk();
        sameThreadRejectsOverlapAndResumesAfterTerminal();
        targetedCancelDoesNotAbortSiblingTurn();
        userInputResponseDoesNotCrossThreads();
        mismatchedLaneDoesNotReplaceHealthyChild();
        rateLimitProbeKeepsResidentChildHealthy();
    }

    private void rateLimitProbeDoesNotCreateChildBeforeFirstTask() throws Exception {
        JsonNode before = workerHealth();
        assertThat(before.at("/runtime_metrics/pool/instances").asInt()).isZero();
        assertThat(before.at("/runtime_metrics/pool/created_total").asInt()).isZero();

        JsonNode rateLimits = getNavigator("/api/v1/codex-runtimes/" + runtime.getRuntimeId()
                + "/revisions/" + runtime.getRevision() + "/rate-limits?refresh=true");
        assertThat(rateLimits.path("code").asInt()).isEqualTo(200);
        assertThat(rateLimits.at("/data/state").asText()).isEqualTo("UNKNOWN");
        assertThat(rateLimits.at("/data/errorCode").asText())
                .isEqualTo("RATE_LIMITS_SOURCE_UNAVAILABLE");

        JsonNode after = workerHealth();
        assertThat(after.at("/runtime_metrics/pool/instances").asInt()).isZero();
        assertThat(after.at("/runtime_metrics/pool/creating").asInt()).isZero();
        assertThat(after.at("/runtime_metrics/pool/created_total").asInt()).isZero();
    }

    @Test
    void forwardToNewSessionKeepsAppServerRuntimeAffinity() throws Exception {
        String sourceTrace = trace("forward-source");
        registerFinal(sourceTrace, 0, "SOURCE_" + sourceTrace);
        JsonNode sourceTask = createTask(sourceTrace, MODEL_ID, null, false);
        JsonNode sourceCompleted = awaitTask(sourceTask.path("taskId").asText(), "COMPLETED");
        String sourceMessageId = awaitAssistantMessageId(sourceTask.path("taskId").asText());

        String targetTrace = trace("forward-target");
        registerFinal(targetTrace, 0, "RESULT_" + targetTrace);
        JsonNode forward = postNavigator("/api/v1/session-relations/forward", Map.of(
                "sourceSessionId", sourceCompleted.path("sessionId").asText(),
                "sourceMessageId", sourceMessageId,
                "targetMode", "NEW_SESSION",
                "workerId", workerId,
                "directoryId", DIRECTORY_ID,
                "cwd", CWD,
                "prompt", "BUG-010 E2E " + targetTrace + " next:" + targetTrace + ":001",
                "model", MODEL,
                "modelConfigId", MODEL_ID,
                "permissionMode", "bypassPermissions"
        ));

        assertThat(forward.path("code").asInt()).as(forward.toPrettyString()).isEqualTo(200);
        assertThat(forward.at("/data/targetMode").asText()).isEqualTo("NEW_SESSION");
        assertThat(forward.at("/data/relationId").asLong()).isPositive();
        JsonNode forwardedTask = forward.at("/data/task");
        assertThat(forwardedTask.path("providerType").asText()).isEqualTo(PROVIDER);
        JsonNode completed = awaitTask(forwardedTask.path("taskId").asText(), "COMPLETED");
        assertThat(completed.path("resultText").asText()).contains("RESULT_" + targetTrace);

        var persistedTask = codexTaskRepository.findByTaskId(forwardedTask.path("taskId").asText()).orElseThrow();
        assertThat(persistedTask.getRuntimeType()).isEqualTo("APP_SERVER");
        assertThat(persistedTask.getRuntimeId()).isEqualTo(runtime.getRuntimeId());
        assertThat(persistedTask.getRuntimeRevision()).isEqualTo(runtime.getRevision());

        var targetSession = sessionRepository.findById(forward.at("/data/targetSessionId").asText()).orElseThrow();
        assertThat(targetSession.getProviderType()).isEqualTo(PROVIDER);
        assertThat(targetSession.getCurrentWorkerId()).isEqualTo(workerId);
        assertThat(targetSession.getCurrentDirectoryId()).isEqualTo(DIRECTORY_ID);
        assertThat(targetSession.getLatestTaskId()).isEqualTo(forwardedTask.path("taskId").asText());
    }

    private void differentThreadsShareOneChildWithoutCrossTalk() throws Exception {
        String traceA = trace("parallel-a");
        String traceB = trace("parallel-b");
        registerFinal(traceA, 2_000, "RESULT_" + traceA);
        registerFinal(traceB, 2_000, "RESULT_" + traceB);

        JsonNode taskA = createTask(traceA, MODEL_ID, null, false);
        JsonNode taskB = createTask(traceB, MODEL_ID, null, false);
        awaitWorkerHealth(health -> health.path("active_tasks").asInt() >= 2
                && health.at("/runtime_metrics/pool/instances").asInt() == 1
                && health.at("/runtime_metrics/pool/busy").asInt() == 1);
        JsonNode health = workerHealth();
        assertThat(health.at("/runtime_metrics/pool/instances").asInt()).isEqualTo(1);
        assertThat(health.at("/runtime_metrics/pool/busy").asInt()).isEqualTo(1);

        JsonNode completedA = awaitTask(taskA.path("taskId").asText(), "COMPLETED");
        JsonNode completedB = awaitTask(taskB.path("taskId").asText(), "COMPLETED");
        assertThat(completedA.path("resultText").asText()).contains("RESULT_" + traceA).doesNotContain(traceB);
        assertThat(completedB.path("resultText").asText()).contains("RESULT_" + traceB).doesNotContain(traceA);
        assertThat(completedA.path("codexThreadId").asText()).isNotEqualTo(completedB.path("codexThreadId").asText());
        assertMockMatchedOnce(traceA);
        assertMockMatchedOnce(traceB);
    }

    private void sameThreadRejectsOverlapAndResumesAfterTerminal() throws Exception {
        String traceFirst = trace("same-thread-first");
        registerFinal(traceFirst, 2_500, "RESULT_" + traceFirst);
        JsonNode first = createTask(traceFirst, MODEL_ID, null, false);
        String firstTaskId = first.path("taskId").asText();
        JsonNode running = awaitTask(firstTaskId, task -> task.path("codexThreadId").isTextual());

        JsonNode overlap = rawTaskRequest(trace("same-thread-overlap"), MODEL_ID,
                running.path("sessionId").asText(), true);
        assertThat(overlap.path("code").asInt()).isNotEqualTo(200);
        assertThat(overlap.toString()).contains("正在运行任务");

        JsonNode completed = awaitTask(firstTaskId, "COMPLETED");
        String traceNext = trace("same-thread-next");
        registerFinal(traceNext, 0, "RESULT_" + traceNext);
        JsonNode next = createTask(traceNext, MODEL_ID, completed.path("sessionId").asText(), true);
        JsonNode nextCompleted = awaitTask(next.path("taskId").asText(), "COMPLETED");
        assertThat(nextCompleted.path("codexThreadId").asText()).isEqualTo(completed.path("codexThreadId").asText());
        assertThat(nextCompleted.path("resultText").asText()).contains("RESULT_" + traceNext);
    }

    private void targetedCancelDoesNotAbortSiblingTurn() throws Exception {
        String traceCancel = trace("cancel-target");
        String traceKeep = trace("cancel-sibling");
        registerInterruptibleShell(traceCancel);
        registerFinal(traceKeep, 1_500, "RESULT_" + traceKeep);
        JsonNode cancelTask = createTask(traceCancel, MODEL_ID, null, false);
        JsonNode keepTask = createTask(traceKeep, MODEL_ID, null, false);
        JsonNode cancelTurn = awaitWorkerTurn(cancelTask.path("taskId").asText());
        JsonNode keepTurn = awaitWorkerTurn(keepTask.path("taskId").asText());
        assertThat(cancelTurn.path("thread_id").asText()).isNotEqualTo(keepTurn.path("thread_id").asText());
        assertThat(cancelTurn.path("turn_id").asText()).isNotEqualTo(keepTurn.path("turn_id").asText());
        awaitMockMatched(traceCancel, 1);
        Thread.sleep(500);

        JsonNode cancel = postNavigator("/api/v1/tasks/" + cancelTask.path("taskId").asText() + "/cancel", Map.of());
        assertThat(cancel.path("code").asInt()).isEqualTo(200);
        JsonNode cancelled = awaitTask(cancelTask.path("taskId").asText(), "ABORTED");
        JsonNode sibling = awaitTask(keepTask.path("taskId").asText(), "COMPLETED");
        assertThat(cancelled.path("resultText").asText("")).doesNotContain(traceKeep);
        assertThat(sibling.path("resultText").asText()).contains("RESULT_" + traceKeep).doesNotContain(traceCancel);
        assertThat(workerHealth().at("/runtime_metrics/pool/instances").asInt()).isEqualTo(1);
    }

    private void userInputResponseDoesNotCrossThreads() throws Exception {
        String traceInput = trace("user-input");
        String traceSibling = trace("user-input-sibling");
        registerUserInput(traceInput);
        registerFinal(traceSibling, 1_500, "RESULT_" + traceSibling);
        JsonNode inputTask = createTask(traceInput, MODEL_ID, null, false);
        JsonNode siblingTask = createTask(traceSibling, MODEL_ID, null, false);
        JsonNode awaiting = awaitTask(inputTask.path("taskId").asText(), "AWAITING_INPUT");
        SessionTaskEntity state = sessionTaskRepository.findByTaskId(awaiting.path("taskId").asText()).orElseThrow();
        JsonNode pending = objectMapper.readTree(state.getTaskStateJson()).path("codexPendingInteraction");
        JsonNode rawRequestId = pending.path("request_id");
        String requestIdType = rawRequestId.isIntegralNumber() ? "number" : "string";
        String externalRequestId = "task:" + awaiting.path("taskId").asText()
                + ":" + requestIdType + ":" + rawRequestId.asText();
        JsonNode response = postNavigator("/api/v1/tasks/" + awaiting.path("taskId").asText() + "/respond", Map.of(
                "permissionId", externalRequestId,
                "answers", Map.of("confirm_path", "next:" + traceInput + ":002")
        ));
        assertThat(response.path("code").asInt())
                .withFailMessage("request_user_input response failed: %s", response)
                .isEqualTo(200);

        JsonNode inputCompleted = awaitTask(awaiting.path("taskId").asText(), "COMPLETED");
        JsonNode siblingCompleted = awaitTask(siblingTask.path("taskId").asText(), "COMPLETED");
        assertThat(inputCompleted.path("resultText").asText()).contains("RESULT_" + traceInput).doesNotContain(traceSibling);
        assertThat(siblingCompleted.path("resultText").asText()).contains("RESULT_" + traceSibling).doesNotContain(traceInput);
        assertMockMatchedAtLeast(traceInput, 2);
    }

    private void mismatchedLaneDoesNotReplaceHealthyChild() throws Exception {
        JsonNode before = workerHealth();
        int createdBefore = before.at("/runtime_metrics/pool/created_total").asInt();
        String traceMismatch = trace("lane-mismatch");
        registerFinal(traceMismatch, 0, "MUST_NOT_COMPLETE_" + traceMismatch);
        JsonNode mismatch = createTask(traceMismatch, MISMATCH_MODEL_ID, null, false);
        JsonNode failed = awaitTask(mismatch.path("taskId").asText(), "FAILED");
        assertThat(failed.toString()).contains("APP_SERVER_POOL_SINGLE_INSTANCE_LANE_MISMATCH");

        JsonNode afterFailure = workerHealth();
        assertThat(afterFailure.at("/runtime_metrics/pool/instances").asInt()).isEqualTo(1);
        assertThat(afterFailure.at("/runtime_metrics/pool/created_total").asInt()).isEqualTo(createdBefore);

        String traceHealthy = trace("lane-after-reject");
        registerFinal(traceHealthy, 0, "RESULT_" + traceHealthy);
        JsonNode healthy = createTask(traceHealthy, MODEL_ID, null, false);
        assertThat(awaitTask(healthy.path("taskId").asText(), "COMPLETED").path("resultText").asText())
                .contains("RESULT_" + traceHealthy);
    }

    private void rateLimitProbeKeepsResidentChildHealthy() throws Exception {
        JsonNode before = workerHealth();
        int createdBefore = before.at("/runtime_metrics/pool/created_total").asInt();
        JsonNode rateLimits = getNavigator("/api/v1/codex-runtimes/" + runtime.getRuntimeId()
                + "/revisions/" + runtime.getRevision() + "/rate-limits?refresh=true");
        assertThat(rateLimits.path("code").asInt()).isEqualTo(200);
        JsonNode after = workerHealth();
        assertThat(after.at("/runtime_metrics/pool/instances").asInt()).isEqualTo(1);
        assertThat(after.at("/runtime_metrics/pool/created_total").asInt()).isEqualTo(createdBefore);
        assertThat(after.path("ready").asBoolean()).isTrue();
    }

    private void saveModel(String id, String apiKey) {
        LlmModelConfigEntity model = new LlmModelConfigEntity();
        model.setId(id);
        model.setTenantId(TENANT_ID);
        model.setName(id);
        model.setCategory(LlmModelCategory.CODING);
        model.setBaseUrl(mockBaseUrl + "/v1");
        model.setModelName(MODEL);
        model.setApiKey(credentialEncryptor.encrypt(apiKey));
        model.setIsDefault(false);
        model.setScope(ModelAccessScope.GLOBAL);
        model.setSortOrder(0);
        model.setWorkerBackend("OPENAI_CODEX_APP_SERVER");
        model.setAvailableModels("[\"" + MODEL + "\"]");
        model.setOwnerId(TENANT_ID);
        model.setEnabled(true);
        modelRepository.save(model);
    }

    private CodexRuntimeDTO promoteToAllDefault(CodexRuntimeDTO current) {
        CodexRuntimeDTO value = current;
        for (String policy : List.of("ULTRA_CANARY", "ULTRA_DEFAULT", "ALL_CANARY", "ALL_DEFAULT")) {
            CodexRuntimeRoutingForm form = new CodexRuntimeRoutingForm();
            form.setEnabled(true);
            form.setRoutingPolicy(policy);
            form.setRolloutPercentage(100);
            form.setExpectedRoutingEpoch(value.getRoutingEpoch());
            value = runtimeRegistryService.updateRouting(value.getRuntimeId(), value.getRevision(), form);
        }
        return value;
    }

    private String login() throws Exception {
        JsonNode envelope = postJson(navigatorBaseUrl() + "/api/v1/auth/login", Map.of(
                "username", USERNAME,
                "password", PASSWORD
        ), null);
        assertThat(envelope.path("code").asInt()).isEqualTo(200);
        return envelope.at("/data/token").asText();
    }

    private JsonNode createTask(String traceId, String modelConfigId, String sessionId, boolean resume) throws Exception {
        JsonNode envelope = rawTaskRequest(traceId, modelConfigId, sessionId, resume);
        assertThat(envelope.path("code").asInt()).as(envelope.toPrettyString()).isEqualTo(200);
        return envelope.path("data");
    }

    private JsonNode rawTaskRequest(String traceId, String modelConfigId, String sessionId, boolean resume) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", workerId);
        body.put("prompt", "BUG-007 E2E " + traceId + " next:" + traceId + ":001");
        body.put("providerType", PROVIDER);
        body.put("directoryId", DIRECTORY_ID);
        body.put("cwd", CWD);
        body.put("model", MODEL);
        body.put("modelConfigId", modelConfigId);
        body.put("permissionMode", "bypassPermissions");
        if (sessionId != null) body.put("sessionId", sessionId);
        return postNavigator(resume ? "/api/v1/tasks/resume" : "/api/v1/tasks", body);
    }

    private JsonNode awaitTask(String taskId, String expectedStatus) throws Exception {
        return awaitTask(taskId, task -> expectedStatus.equals(task.path("status").asText()));
    }

    private JsonNode awaitTask(String taskId, Predicate<JsonNode> predicate) throws Exception {
        long deadline = System.nanoTime() + TASK_TIMEOUT.toNanos();
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            JsonNode envelope = getNavigator("/api/v1/tasks/" + taskId);
            if (envelope.path("code").asInt() == 200) {
                latest = envelope.path("data");
                if (predicate.test(latest)) return latest;
                if (List.of("FAILED", "ABORTED").contains(latest.path("status").asText())) {
                    throw new AssertionError("Task reached unexpected terminal state: " + latest.toPrettyString());
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for task " + taskId + ": " + latest);
    }

    private String awaitAssistantMessageId(String taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var message = sessionMessageRepository.findFirstByTaskIdOrderByCreatedAtDescIdDesc(taskId)
                    .filter(candidate -> "ASSISTANT".equalsIgnoreCase(candidate.getRole()));
            if (message.isPresent()) return message.get().getId();
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for assistant message for task " + taskId);
    }

    private JsonNode awaitWorkerHealth(Predicate<JsonNode> predicate) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            latest = workerHealth();
            if (predicate.test(latest)) return latest;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for Worker health: " + latest);
    }

    private JsonNode awaitWorkerTurn(String taskId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(workerBaseUrl + "/api/v1/tasks/" + taskId + "/status"))
                    .timeout(REQUEST_TIMEOUT)
                    .GET();
            if (!workerToken.isBlank()) builder.header("Authorization", "Bearer " + workerToken);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                Thread.sleep(50);
                continue;
            }
            assertThat(response.statusCode())
                    .as("Worker turn affinity " + taskId + " -> " + response.body())
                    .isBetween(200, 299);
            latest = objectMapper.readTree(response.body());
            if (latest.path("thread_id").isTextual()
                    && !latest.path("thread_id").asText().isBlank()
                    && latest.path("turn_id").isTextual()
                    && !latest.path("turn_id").asText().isBlank()) {
                return latest;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for Worker turn affinity " + taskId + ": " + latest);
    }

    private void registerFinal(String traceId, int delayMs, String result) throws Exception {
        registerScript(traceId, List.of(Map.of(
                "cursor", "next:" + traceId + ":001",
                "response", Map.of("delay_ms", delayMs, "content", result)
        )));
    }

    private void registerInterruptibleShell(String traceId) throws Exception {
        Map<String, Object> shellCall = Map.of(
                "id", "sleep_" + traceId,
                "type", "function",
                "function", Map.of(
                        "name", "shell_command",
                        "arguments", Map.of(
                                "command", "sleep 20; printf 'next:" + traceId + ":002\\n'",
                                "workdir", CWD,
                                "timeout_ms", 25_000))
        );
        registerScript(traceId, List.of(
                Map.of("cursor", "next:" + traceId + ":001", "response", Map.of("tool_calls", List.of(shellCall))),
                Map.of("cursor", "next:" + traceId + ":002", "response", Map.of("content", "MUST_NOT_COMPLETE_" + traceId))
        ));
    }

    private void registerUserInput(String traceId) throws Exception {
        Map<String, Object> function = Map.of(
                "id", "request_" + traceId,
                "type", "function",
                "function", Map.of(
                        "name", "request_user_input",
                        "arguments", Map.of("questions", List.of(Map.of(
                                "id", "confirm_path",
                                "header", "Confirm",
                                "question", "Continue this exact task?",
                                "options", List.of(
                                        Map.of("label", "next:" + traceId + ":002", "description", "Continue this scripted turn."),
                                        Map.of("label", "No", "description", "Stop."))))))
        );
        registerScript(traceId, List.of(
                Map.of("cursor", "next:" + traceId + ":001", "response", Map.of("tool_calls", List.of(function))),
                Map.of("cursor", "next:" + traceId + ":002", "response", Map.of("content", "RESULT_" + traceId))
        ));
    }

    private void registerScript(String traceId, List<Map<String, Object>> turns) throws Exception {
        JsonNode response = postExternalJson(mockBaseUrl + "/__e2e/scripts", Map.of(
                "traceId", traceId,
                "scenarioId", "bug007-navigator-app-server",
                "turns", turns
        ));
        assertThat(response.path("traceId").asText()).isEqualTo(traceId);
    }

    private void assertMockMatchedOnce(String traceId) throws Exception {
        assertMockMatchedAtLeast(traceId, 1);
        JsonNode records = getJson(mockBaseUrl + "/__debug/requests?traceId=" + traceId, null);
        assertThat(records.size()).isEqualTo(1);
    }

    private void assertMockMatchedAtLeast(String traceId, int count) throws Exception {
        JsonNode records = awaitMockMatched(traceId, count);
        assertThat(records.size()).isGreaterThanOrEqualTo(count);
        for (JsonNode record : records) {
            assertThat(record.path("matched").asBoolean()).isTrue();
            assertThat(record.at("/responseSummary/protocol").asText()).isEqualTo("responses");
        }
    }

    private JsonNode awaitMockMatched(String traceId, int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            latest = getJson(mockBaseUrl + "/__debug/requests?traceId=" + traceId, null);
            if (latest.size() >= count) return latest;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for mock Responses request " + traceId + ": " + latest);
    }

    private JsonNode workerHealth() throws Exception {
        return getJson(workerBaseUrl + "/health", workerToken.isBlank() ? null : workerToken);
    }

    private JsonNode getNavigator(String path) throws Exception {
        return getJson(navigatorBaseUrl() + path, authToken);
    }

    private JsonNode postNavigator(String path, Object body) throws Exception {
        return postJson(navigatorBaseUrl() + path, body, authToken);
    }

    private JsonNode getJson(String url, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        if (bearer != null && !bearer.isBlank()) builder.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(url + " -> " + response.body()).isBetween(200, 299);
        return objectMapper.readTree(response.body());
    }

    private JsonNode postJson(String url, Object body, String bearer) throws Exception {
        String payload = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (bearer != null && !bearer.isBlank()) builder.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as(url + " request=" + payload + " response=" + response.body())
                .isBetween(200, 599);
        return response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
    }

    private JsonNode postExternalJson(String url, Object body) throws Exception {
        String payload = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as(url + " request=" + payload + " response=" + response.body())
                .isBetween(200, 299);
        return response.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
    }

    private String navigatorBaseUrl() {
        return "http://127.0.0.1:" + navigatorPort;
    }

    private String trace(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing system property: " + name);
        return value;
    }
}
