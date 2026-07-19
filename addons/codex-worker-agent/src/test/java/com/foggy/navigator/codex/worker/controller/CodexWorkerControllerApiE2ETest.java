package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-slice regression test for the Navigator control-plane route.  The
 * substitute Worker deliberately delays its process snapshot past the
 * controller's five-second preflight window; no real Worker or OS process is
 * involved.
 */
@ExtendWith(MockitoExtension.class)
class CodexWorkerControllerApiE2ETest {

    @Mock
    private WorkerManagementFacade workerManagementFacade;

    @Mock
    private CodexTaskService taskService;

    private HttpServer delayedWorker;
    private MockMvc client;
    private volatile boolean delayProcessResponse = true;
    private volatile int processResponseStatus = 200;

    @BeforeEach
    void setUp() throws Exception {
        delayedWorker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        delayedWorker.createContext("/api/v1/processes", exchange -> {
            try {
                if (delayProcessResponse) {
                    Thread.sleep(6_000);
                }
                String responseText = processResponseStatus == 200
                        ? "{\"processes\":[]}"
                        : "{\"detail\":\"Worker diagnostic must remain private\"}";
                byte[] response = responseText.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(processResponseStatus, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        delayedWorker.start();

        String baseUrl = "http://127.0.0.1:" + delayedWorker.getAddress().getPort();
        when(workerManagementFacade.getCodexConfig("worker-e2e"))
                .thenReturn(CodexConfig.builder().baseUrl(baseUrl).authToken("test-worker-token").build());

        CodexWorkerController controller = new CodexWorkerController(
                workerManagementFacade, new CodexWorkerClientFactory(), taskService);
        client = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        if (delayedWorker != null) delayedWorker.stop(0);
    }

    @Test
    void pidKillPreflightTimeoutMustExposeStableTimeoutCodeInsteadOfGenericUnconfirmedCode() throws Exception {
        String body = client.perform(post("/api/v1/codex-workers/worker-e2e/processes/2599450/kill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"force\":false,\"taskId\":\"task-e2e\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("CODEX_WORKER_TIMEOUT"),
                "Worker preflight timeout must not be collapsed into REQUEST_UNCONFIRMED");
        verifyNoInteractions(taskService);
    }

    @Test
    void pidKillPreflightHttpRejectionMustKeepStatusCodeAndHideWorkerResponseBody() throws Exception {
        delayProcessResponse = false;
        processResponseStatus = 502;

        String body = client.perform(post("/api/v1/codex-workers/worker-e2e/processes/2599450/kill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"force\":false,\"taskId\":\"task-e2e\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("CODEX_WORKER_HTTP_502"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("Worker diagnostic"));
        verifyNoInteractions(taskService);
    }
}
