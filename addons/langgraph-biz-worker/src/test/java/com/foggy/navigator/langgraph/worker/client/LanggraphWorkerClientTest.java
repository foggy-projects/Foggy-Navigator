package com.foggy.navigator.langgraph.worker.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanggraphWorkerClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void streamQuery_sendsAttachmentsInRequestBody() throws Exception {
        try (CaptureServer server = CaptureServer.start()) {
            LanggraphWorkerClient client = new LanggraphWorkerClient("worker-1", server.baseUrl(), "token");
            List<Map<String, Object>> attachments = List.of(Map.of(
                    "name", "pod-photo.png",
                    "url", "https://tms.example.com/files/pod-photo.png",
                    "kind", "image"
            ));

            client.streamQuery(
                    "describe",
                    Map.of("source", "test"),
                    Map.of("formId", "tms-1"),
                    "gemini-2.5-pro",
                    "model-config-1",
                    "task-1",
                    "session-1",
                    "user-1",
                    "tenant-1",
                    attachments
            ).blockFirst(Duration.ofSeconds(5));

            Map<String, Object> body = objectMapper.readValue(server.body(),
                    new TypeReference<>() {});
            assertEquals(attachments, body.get("attachments"));
        }
    }

    private static class CaptureServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> body = new AtomicReference<>();

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
            server.start();
            return capture;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String body() {
            return body.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
