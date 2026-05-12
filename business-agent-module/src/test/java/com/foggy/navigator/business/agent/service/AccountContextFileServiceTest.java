package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.AccountContextFileDTO;
import com.foggy.navigator.business.agent.model.dto.AccountContextFileTreeDTO;
import com.foggy.navigator.business.agent.model.form.AccountContextFileWriteForm;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AccountContextFileServiceTest {

    @Mock
    private ClientAppUserGrantService userGrantService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AccountContextFileService service;

    @Test
    void listChecksGrantAndMapsWorkerResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/account-context/accounts/user-1/files", exchange -> {
            byte[] response = """
                    {"account_id":"user-1","files":[{"file_name":"ACCOUNT_POLICY.md","exists":true,"size":7,"line_count":1,"sha256":"abc","truncated":false,"writable":true}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        try {
            ReflectionTestUtils.setField(service, "devSyncWorkerUrl", "http://localhost:" + server.getAddress().getPort());

            AccountContextFileTreeDTO tree = service.list("tenant-1", "app-1", "user-1");

            verify(userGrantService).checkUpstreamUserAccess("tenant-1", "app-1", "user-1");
            assertEquals("user-1", tree.getAccountId());
            assertEquals("ACCOUNT_POLICY.md", tree.getFiles().get(0).getFileName());
            assertTrue(tree.getFiles().get(0).isWritable());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void writePolicyPostsContentAndExpectedSha() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/account-context/accounts/user-1/files/ACCOUNT_POLICY.md", exchange -> {
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"file_name":"ACCOUNT_POLICY.md","exists":true,"size":10,"line_count":1,"sha256":"def","truncated":false,"writable":true}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        try {
            ReflectionTestUtils.setField(service, "devSyncWorkerUrl", "http://localhost:" + server.getAddress().getPort());
            AccountContextFileWriteForm form = new AccountContextFileWriteForm();
            form.setContent("policy v2\n");
            form.setExpectedSha256("abc");

            AccountContextFileDTO dto = service.writePolicy("tenant-1", "app-1", "user-1", "ACCOUNT_POLICY.md", form);

            assertEquals("ACCOUNT_POLICY.md", dto.getFileName());
            assertEquals("def", dto.getSha256());
            assertNotNull(bodyRef.get());
            assertTrue(bodyRef.get().contains("\"content\":\"policy v2\\n\""));
            assertTrue(bodyRef.get().contains("\"expected_sha256\":\"abc\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void writePolicyRejectsNonPolicyAndSensitiveValues() {
        AccountContextFileWriteForm form = new AccountContextFileWriteForm();
        form.setContent("policy\n");

        assertThrows(IllegalArgumentException.class,
                () -> service.writePolicy("tenant-1", "app-1", "user-1", "AGENT.md", form));

        AccountContextFileWriteForm sensitive = new AccountContextFileWriteForm();
        sensitive.setContent("Authorization: Bearer abc");
        assertThrows(IllegalArgumentException.class,
                () -> service.writePolicy("tenant-1", "app-1", "user-1", "ACCOUNT_POLICY.md", sensitive));
    }

    @Test
    void writePolicyAllowsDenyRulesThatMentionSensitiveFieldNames() {
        AccountContextFileWriteForm form = new AccountContextFileWriteForm();
        form.setContent("Do not store tokens, adapterConfigJson, manifestJson, or task_scoped_token.");

        assertThrows(IllegalStateException.class,
                () -> service.writePolicy("tenant-1", "app-1", "user-1", "ACCOUNT_POLICY.md", form));
    }
}
