package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.session.dto.ErrorDiagnosticDTO;
import com.foggy.navigator.session.dto.ErrorDiagnosticShareDTO;
import com.foggy.navigator.session.service.ErrorDiagnosticService;
import com.foggyframework.core.ex.RX;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ErrorDiagnosticController {

    private final ErrorDiagnosticService diagnosticService;

    @RequireAuth
    @GetMapping("/api/v1/error-diagnostics/{diagnosticId}")
    public RX<ErrorDiagnosticDTO> get(@PathVariable String diagnosticId) {
        CurrentUser user = UserContext.getCurrentUser();
        try {
            return RX.ok(diagnosticService.getOwned(diagnosticId, user.getUserId(), user.getTenantId()));
        } catch (RuntimeException e) {
            return RX.failB("Diagnostic not available");
        }
    }

    @RequireAuth
    @GetMapping("/api/v1/error-diagnostics/{diagnosticId}/shares")
    public RX<List<ErrorDiagnosticShareDTO>> listShares(@PathVariable String diagnosticId) {
        CurrentUser user = UserContext.getCurrentUser();
        try {
            return RX.ok(diagnosticService.listShares(diagnosticId, user.getUserId(), user.getTenantId()));
        } catch (RuntimeException e) {
            return RX.failB("Diagnostic not available");
        }
    }

    @RequireAuth
    @PostMapping("/api/v1/error-diagnostics/{diagnosticId}/shares")
    public RX<ErrorDiagnosticShareDTO> createShare(@PathVariable String diagnosticId,
                                                   @RequestBody(required = false) CreateShareForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        try {
            return RX.ok(diagnosticService.createShare(
                    diagnosticId, user.getUserId(), user.getTenantId(), form != null ? form.getDays() : null));
        } catch (RuntimeException e) {
            return RX.failB("Diagnostic not available");
        }
    }

    @RequireAuth
    @DeleteMapping("/api/v1/error-diagnostics/{diagnosticId}/shares/{shareId}")
    public RX<Void> revokeShare(@PathVariable String diagnosticId, @PathVariable String shareId) {
        CurrentUser user = UserContext.getCurrentUser();
        try {
            diagnosticService.revokeShare(diagnosticId, shareId, user.getUserId(), user.getTenantId());
            return RX.ok();
        } catch (RuntimeException e) {
            return RX.failB("Diagnostic not available");
        }
    }

    @GetMapping("/api/v1/diagnostic-shares/{token}")
    public ResponseEntity<RX<ErrorDiagnosticDTO>> publicDiagnostic(@PathVariable String token) {
        HttpHeaders headers = publicHeaders("default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        try {
            return ResponseEntity.ok().headers(headers).body(RX.ok(diagnosticService.getPublic(token)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).headers(headers).body(RX.failB("Diagnostic not available"));
        }
    }

    static HttpHeaders publicHeaders(String csp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Robots-Tag", "noindex, nofollow");
        headers.set("Content-Security-Policy", csp);
        headers.set("X-Content-Type-Options", "nosniff");
        return headers;
    }

    @Data
    public static class CreateShareForm {
        private Integer days;
    }
}
