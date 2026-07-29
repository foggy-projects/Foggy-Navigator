package com.foggy.navigator.sdk.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.sdk.NavigatorClient;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskReconcileForm;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskReconciliationDTO;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskTerminateForm;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskTerminationDTO;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTerminationReadinessDTO;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskReconciliationState;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTaskTerminationOutcome;
import com.foggy.navigator.sdk.model.businessagent.RuntimeTerminationCapability;
import com.foggy.navigator.sdk.model.businessagent.RuntimeWorkerIdentityMatch;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTaskTypedContractTest {

    private static HttpServer server;
    private static int port;
    private static String response;
    private static String lastPath;
    private static String lastMethod;
    private static String lastBody;
    private static String lastClientRequestId;
    private static String lastUpstreamUserId;
    private NavigatorClient client;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().toString();
            lastMethod = exchange.getRequestMethod();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastClientRequestId =
                    exchange.getRequestHeaders().getFirst("X-Navigator-Client-Request-Id");
            lastUpstreamUserId = exchange.getRequestHeaders().getFirst("X-Upstream-User-Id");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        client = NavigatorClient.builder()
                .baseUrl("http://localhost:" + port)
                .noDefaultAuth()
                .timeout(Duration.ofSeconds(5))
                .build();
        response = "{\"code\":200,\"data\":{}}";
        lastPath = null;
        lastMethod = null;
        lastBody = null;
        lastClientRequestId = null;
        lastUpstreamUserId = null;
    }

    @Test
    void readinessUsesFormalTypedResult() {
        response = """
                {"code":200,"data":{
                  "taskId":"task-a",
                  "expectedPhysicalWorkerId":"worker-a",
                  "selectedPhysicalWorkerId":"worker-a",
                  "workerIdentityMatch":"MATCHED",
                  "terminationCapability":"SUPPORTED",
                  "currentTaskStatus":"RUNNING",
                  "canonicalTerminal":false,
                  "reasonCode":"TERMINATION_READY",
                  "terminationRequestReceiptEnabled":true
                }}
                """;

        RuntimeTerminationReadinessDTO result = client.businessAgent()
                .getRuntimeTerminationReadiness(
                        "app-key", "app-secret", "user-a", "task-a", "worker-a");

        assertEquals(RuntimeWorkerIdentityMatch.MATCHED, result.getWorkerIdentityMatch());
        assertEquals(RuntimeTerminationCapability.SUPPORTED, result.getTerminationCapability());
        assertFalse(result.getCanonicalTerminal());
        assertEquals("TERMINATION_READY", result.getReasonCode());
        assertTrue(result.getTerminationRequestReceiptEnabled());
        assertEquals("GET", lastMethod);
        assertTrue(lastPath.contains("taskId=task-a"));
        assertEquals("user-a", lastUpstreamUserId);
    }

    @Test
    void terminateSerializesTypedFormAndPreservesRequestId() {
        response = """
                {"code":200,"data":{
                  "clientRequestId":"c66ebce8-e7ae-45d2-9576-32454a960a4f",
                  "taskId":"task-a",
                  "outcome":"ACCEPTED",
                  "currentTaskStatus":"CANCEL_REQUESTED",
                  "canonicalTerminal":false,
                  "reasonCode":"TERMINATION_REQUEST_ACCEPTED",
                  "terminationRequestReceiptPersisted":true,
                  "requestReconciliationAvailable":true
                }}
                """;
        RuntimeTaskTerminateForm form = new RuntimeTaskTerminateForm();
        form.setTaskId("task-a");
        form.setExpectedPhysicalWorkerId("worker-a");
        form.setReason("operator-request");
        form.setDryRun(false);
        form.setConfirmTaskId("task-a");

        RuntimeTaskTerminationDTO result = client.businessAgent().terminateRuntimeTask(
                "app-key", "app-secret", "user-a",
                "c66ebce8-e7ae-45d2-9576-32454a960a4f", form);

        assertEquals(RuntimeTaskTerminationOutcome.ACCEPTED, result.getOutcome());
        assertFalse(result.getCanonicalTerminal());
        assertTrue(result.getTerminationRequestReceiptPersisted());
        assertTrue(result.getRequestReconciliationAvailable());
        assertEquals(lastClientRequestId, result.getClientRequestId());
        assertEquals("POST", lastMethod);
        assertEquals("/api/v1/open/runtime/task-terminate", lastPath);
        assertTrue(lastBody.contains("\"expectedPhysicalWorkerId\":\"worker-a\""));
        assertTrue(lastBody.contains("\"confirmTaskId\":\"task-a\""));
    }

    @Test
    void reconciliationUsesOriginalRequestIdAndOnlyReadOnlyTypedBody() {
        response = """
                {"code":200,"data":{
                  "clientRequestId":"c66ebce8-e7ae-45d2-9576-32454a960a4f",
                  "taskId":"task-a",
                  "reconciliationState":"ACCEPTED",
                  "terminationOutcome":"ACCEPTED",
                  "transition":"CANCEL_REQUESTED",
                  "currentTaskStatus":"CANCEL_REQUESTED",
                  "canonicalTerminal":false,
                  "reasonCode":"TERMINATION_REQUEST_ACCEPTED",
                  "readOnly":true,
                  "sameClientRequestIdReplaySafe":true,
                  "terminationReplayRecommended":false,
                  "newClientRequestIdAllowed":false,
                  "terminationRequestReceiptEnabled":true,
                  "requestReconciliationAvailable":true
                }}
                """;
        RuntimeTaskReconcileForm form = new RuntimeTaskReconcileForm();
        form.setTaskId("task-a");

        RuntimeTaskReconciliationDTO result = client.businessAgent()
                .reconcileRuntimeTaskTermination(
                        "app-key", "app-secret", "user-a",
                        "c66ebce8-e7ae-45d2-9576-32454a960a4f", form);

        assertEquals(RuntimeTaskReconciliationState.ACCEPTED,
                result.getReconciliationState());
        assertTrue(result.getReadOnly());
        assertFalse(result.getCanonicalTerminal());
        assertTrue(result.getTerminationRequestReceiptEnabled());
        assertTrue(result.getRequestReconciliationAvailable());
        assertEquals(lastClientRequestId, result.getClientRequestId());
        assertEquals("{\"taskId\":\"task-a\"}", lastBody);
        assertFalse(lastBody.contains("expectedDispatchCount"));
        assertFalse(lastBody.contains("dryRun"));
    }

    @Test
    void unknownAndNullWireValuesFailClosedInsteadOfBreakingDeserialization() {
        response = """
                {"code":200,"data":{
                  "taskId":"task-a",
                  "workerIdentityMatch":null,
                  "terminationCapability":"FUTURE_CAPABILITY",
                  "currentTaskStatus":null,
                  "canonicalTerminal":null,
                  "reasonCode":null
                }}
                """;

        RuntimeTerminationReadinessDTO result = client.businessAgent()
                .getRuntimeTerminationReadiness(
                        "app-key", "app-secret", "user-a", "task-a", "worker-a");

        assertEquals(RuntimeWorkerIdentityMatch.UNKNOWN, result.getWorkerIdentityMatch());
        assertEquals(RuntimeTerminationCapability.UNKNOWN, result.getTerminationCapability());
        assertEquals("UNKNOWN", result.getCurrentTaskStatus());
        assertEquals("UNKNOWN", result.getReasonCode());
        assertNull(result.getCanonicalTerminal());
        assertFalse(result.getTerminationRequestReceiptEnabled());
    }

    @Test
    void formsAndResultsRoundTripAsJsonWithoutMaps() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RuntimeTaskTerminateForm form = new RuntimeTaskTerminateForm();
        form.setTaskId("task-a");
        form.setExpectedPhysicalWorkerId("worker-a");
        form.setReason("operator-request");
        form.setDryRun(true);

        String json = mapper.writeValueAsString(form);
        RuntimeTaskTerminateForm roundTrip =
                mapper.readValue(json, RuntimeTaskTerminateForm.class);

        assertEquals("task-a", roundTrip.getTaskId());
        assertEquals(Boolean.TRUE, roundTrip.getDryRun());
        assertFalse(json.contains("Map"));

        RuntimeTaskTerminationDTO result = mapper.readValue("""
                {
                  "clientRequestId":"c66ebce8-e7ae-45d2-9576-32454a960a4f",
                  "taskId":"task-a",
                  "outcome":"ALREADY_TERMINAL",
                  "currentTaskStatus":"CANCELLED",
                  "canonicalTerminal":true,
                  "reasonCode":"TASK_ALREADY_TERMINAL",
                  "futureField":"ignored"
                }
                """, RuntimeTaskTerminationDTO.class);
        RuntimeTaskTerminationDTO resultRoundTrip = mapper.readValue(
                mapper.writeValueAsString(result), RuntimeTaskTerminationDTO.class);

        assertEquals(RuntimeTaskTerminationOutcome.ALREADY_TERMINAL,
                resultRoundTrip.getOutcome());
        assertEquals(Boolean.TRUE, resultRoundTrip.getCanonicalTerminal());
        assertEquals("TASK_ALREADY_TERMINAL", resultRoundTrip.getReasonCode());

        RuntimeTaskTerminationDTO futureTermination = mapper.readValue("""
                {
                  "outcome":"FUTURE_OUTCOME",
                  "currentTaskStatus":null,
                  "canonicalTerminal":null,
                  "reasonCode":null
                }
                """, RuntimeTaskTerminationDTO.class);
        assertEquals(RuntimeTaskTerminationOutcome.UNKNOWN,
                futureTermination.getOutcome());
        assertEquals("UNKNOWN", futureTermination.getCurrentTaskStatus());
        assertEquals("UNKNOWN", futureTermination.getReasonCode());
        assertNull(futureTermination.getCanonicalTerminal());

        RuntimeTaskReconciliationDTO futureReconciliation = mapper.readValue("""
                {
                  "reconciliationState":"FUTURE_STATE",
                  "terminationOutcome":null,
                  "transition":null,
                  "currentTaskStatus":null,
                  "canonicalTerminal":null,
                  "reasonCode":null
                }
                """, RuntimeTaskReconciliationDTO.class);
        assertEquals(RuntimeTaskReconciliationState.UNKNOWN,
                futureReconciliation.getReconciliationState());
        assertEquals(RuntimeTaskTerminationOutcome.UNKNOWN,
                futureReconciliation.getTerminationOutcome());
        assertEquals("UNKNOWN", futureReconciliation.getTransition());
        assertEquals("UNKNOWN", futureReconciliation.getCurrentTaskStatus());
        assertEquals("UNKNOWN", futureReconciliation.getReasonCode());
        assertNull(futureReconciliation.getCanonicalTerminal());

        RuntimeTaskTerminationDTO missingReceiptCapability = mapper.readValue(
                """
                {
                  "outcome":"ACCEPTED",
                  "terminationRequestReceiptEnabled":null,
                  "terminationRequestReceiptPersisted":null,
                  "requestReconciliationAvailable":null
                }
                """,
                RuntimeTaskTerminationDTO.class);
        assertFalse(missingReceiptCapability.getTerminationRequestReceiptEnabled());
        assertFalse(missingReceiptCapability.getTerminationRequestReceiptPersisted());
        assertFalse(missingReceiptCapability.getRequestReconciliationAvailable());

        RuntimeTaskReconciliationDTO disabledReconciliation = mapper.readValue(
                """
                {
                  "reconciliationState":"AMBIGUOUS",
                  "reasonCode":"TERMINATION_REQUEST_RECEIPT_DISABLED",
                  "terminationRequestReceiptEnabled":false,
                  "requestReconciliationAvailable":false
                }
                """,
                RuntimeTaskReconciliationDTO.class);
        assertFalse(disabledReconciliation.getTerminationRequestReceiptEnabled());
        assertFalse(disabledReconciliation.getRequestReconciliationAvailable());
    }

    @SuppressWarnings("deprecation")
    @Test
    void legacyMapMethodsRemainSourceCompatible() {
        response = "{\"code\":200,\"data\":{\"taskId\":\"task-a\",\"status\":\"RUNNING\"}}";

        Map<String, Object> readiness = client.businessAgent().runtimeTerminationReadiness(
                "app-key", "app-secret", "user-a", "task-a", "worker-a");

        assertNotNull(readiness);
        assertEquals("task-a", readiness.get("taskId"));
    }
}
