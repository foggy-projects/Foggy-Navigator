package com.foggy.navigator.session.controller;

import com.foggy.navigator.session.dto.ErrorDiagnosticDTO;
import com.foggy.navigator.session.service.ErrorDiagnosticService;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ErrorDiagnosticPublicControllerTest {

    @Mock ErrorDiagnosticService diagnosticService;

    @Test
    void publicJsonHasNoStoreNoReferrerNoIndexAndUniformNotFound() {
        ErrorDiagnosticController controller = new ErrorDiagnosticController(diagnosticService);
        when(diagnosticService.getPublic("missing")).thenThrow(new IllegalArgumentException("hidden cause"));

        ResponseEntity<RX<ErrorDiagnosticDTO>> response = controller.publicDiagnostic("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));
        assertEquals("noindex, nofollow", response.getHeaders().getFirst("X-Robots-Tag"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(response.getHeaders().getFirst("Content-Security-Policy").contains("default-src 'none'"));
        assertNotNull(response.getBody());
        assertEquals("Diagnostic not available", response.getBody().getMsg());
    }

    @Test
    void publicHtmlEscapesAllDiagnosticValuesAndHasNoActiveContent() {
        ErrorDiagnosticSharePageController controller = new ErrorDiagnosticSharePageController(diagnosticService);
        ErrorDiagnosticDTO dto = ErrorDiagnosticDTO.builder()
                .diagnosticId("dg_safe")
                .safeMessage("Failure <script>alert(1)</script>")
                .errorCode("CODEX_WORKER_REMOTE_ERROR")
                .category("RUNTIME")
                .runtimePhase("TURN_EXECUTION")
                .providerType("CODEX")
                .diagnosticText("token & <img src=x onerror=alert(1)>")
                .occurredAt(LocalDateTime.of(2026, 7, 16, 12, 0))
                .shareExpiresAt(LocalDateTime.of(2026, 7, 23, 12, 0))
                .build();
        when(diagnosticService.getPublic("token")).thenReturn(dto);

        ResponseEntity<String> response = controller.page("token");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(response.getHeaders().getFirst("Content-Security-Policy").contains("connect-src 'none'"));
        String html = response.getBody();
        assertNotNull(html);
        assertTrue(html.contains("Failure &lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("token &amp; &lt;img src=x onerror=alert(1)&gt;"));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("http://"));
        assertFalse(html.contains("https://"));
    }
}
