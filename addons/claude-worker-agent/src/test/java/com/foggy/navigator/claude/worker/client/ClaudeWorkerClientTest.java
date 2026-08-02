package com.foggy.navigator.claude.worker.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ClaudeWorkerClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void streamQuery_sendsImagesAndAttachmentsInRequestBody() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            ClaudeWorkerClient client = new ClaudeWorkerClient("worker-1", server.baseUrl(), "token");
            List<Map<String, Object>> attachments = List.of(Map.of(
                    "name", "pod-photo.png",
                    "url", "https://tms.example.com/files/pod-photo.png",
                    "kind", "image"
            ));

            client.streamQuery(
                    "describe",
                    "D:/repo",
                    "session-1",
                    "claude-test",
                    1,
                    null,
                    "[{\"name\":\"screen.png\",\"data\":\"base64\",\"mime_type\":\"image/png\"}]",
                    attachments,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            ).blockFirst(Duration.ofSeconds(5));

            Map<String, Object> body = objectMapper.readValue(server.body(),
                    new TypeReference<>() {});
            assertEquals(attachments, body.get("attachments"));
            assertInstanceOf(List.class, body.get("images"));
        }
    }

    @Test
    void recoveryCallbackRunsAtResponseHeadersAndOnlyOnceAfterStreamEnds() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            ClaudeWorkerClient client = new ClaudeWorkerClient("worker-1", server.baseUrl(), "token");
            AtomicInteger callbacks = new AtomicInteger();
            CountDownLatch callbackObserved = new CountDownLatch(1);

            var subscription = client.subscribeToTask("task-1", 7, () -> {
                        callbacks.incrementAndGet();
                        callbackObserved.countDown();
                    })
                    .subscribe();

            assertEquals(true, server.awaitSubscribeHeaders());
            assertEquals(true, callbackObserved.await(2, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
            assertEquals(false, subscription.isDisposed());

            server.releaseSubscribeBody();
            assertEquals(true, server.awaitSubscribeComplete());
            for (int i = 0; i < 20 && !subscription.isDisposed(); i++) Thread.sleep(10);
            assertEquals(1, callbacks.get());
        }
    }

    @Test
    void recoveryCallbackRunsOnceOnPreResponseConnectionFailure() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ClaudeWorkerClient client = new ClaudeWorkerClient(
                "worker-1", "http://127.0.0.1:" + closedPort, "token");
        AtomicInteger callbacks = new AtomicInteger();

        client.subscribeToTask("task-1", 0, callbacks::incrementAndGet)
                .onErrorResume(ignored -> reactor.core.publisher.Flux.empty())
                .blockLast(Duration.ofSeconds(5));

        assertEquals(1, callbacks.get());
    }

    private static class CaptureServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> body = new AtomicReference<>();
        private final CountDownLatch subscribeHeaders = new CountDownLatch(1);
        private final CountDownLatch subscribeBodyRelease = new CountDownLatch(1);
        private final CountDownLatch subscribeComplete = new CountDownLatch(1);

        private CaptureServer(HttpServer server) {
            this.server = server;
        }

        static CaptureServer start() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CaptureServer capture = new CaptureServer(server);
            server.createContext("/api/v1/query", exchange -> {
                capture.body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = "data: {\"type\":\"done\"}\n\n".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.createContext("/api/v1/tasks/task-1/subscribe", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                capture.subscribeHeaders.countDown();
                try {
                    capture.subscribeBodyRelease.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                    capture.subscribeComplete.countDown();
                }
            });
            server.start();
            return capture;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String body() {
            return body.get();
        }

        boolean awaitSubscribeHeaders() throws InterruptedException {
            return subscribeHeaders.await(2, TimeUnit.SECONDS);
        }

        void releaseSubscribeBody() {
            subscribeBodyRelease.countDown();
        }

        boolean awaitSubscribeComplete() throws InterruptedException {
            return subscribeComplete.await(2, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            subscribeBodyRelease.countDown();
            server.stop(0);
        }
    }
}
