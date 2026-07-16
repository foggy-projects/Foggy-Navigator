package com.foggy.navigator.session.controller;

import com.foggy.navigator.session.dto.ErrorDiagnosticDTO;
import com.foggy.navigator.session.service.ErrorDiagnosticService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;

/** Self-contained anonymous page with no scripts, fonts or third-party requests. */
@RestController
@RequiredArgsConstructor
public class ErrorDiagnosticSharePageController {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ErrorDiagnosticService diagnosticService;

    @GetMapping(value = "/diagnostic-share/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String token) {
        HttpHeaders headers = ErrorDiagnosticController.publicHeaders(
                "default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; font-src 'none'; "
                        + "connect-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        try {
            return ResponseEntity.ok().headers(headers).body(render(diagnosticService.getPublic(token)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(404).headers(headers).body(renderUnavailable());
        }
    }

    private static String render(ErrorDiagnosticDTO value) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex,nofollow\"><title>错误诊断</title>"
                + style() + "</head><body><main><div class=\"badge\">只读诊断快照</div>"
                + "<h1>" + esc(value.getSafeMessage()) + "</h1>"
                + "<p class=\"lead\">此页面仅包含已脱敏、限长的排障信息。</p>"
                + grid(row("错误码", value.getErrorCode())
                + row("类别", value.getCategory())
                + row("运行阶段", value.getRuntimePhase())
                + row("Provider / Runtime", join(value.getProviderType(), value.getRuntimeType()))
                + row("Provider 状态", value.getProviderStatus())
                + row("HTTP 状态", value.getHttpStatus() != null ? value.getHttpStatus().toString() : null)
                + row("异常类型", value.getExceptionType())
                + row("发生时间", format(value.getOccurredAt()))
                + row("诊断编号", value.getDiagnosticId())
                + row("链接过期", format(value.getShareExpiresAt())))
                + diagnosticText(value.getDiagnosticText())
                + "<footer>Foggy Navigator · 临时诊断分享</footer></main></body></html>";
    }

    private static String renderUnavailable() {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex,nofollow\"><title>诊断不可用</title>"
                + style() + "</head><body><main><div class=\"badge\">链接不可用</div>"
                + "<h1>无法查看此诊断</h1><p class=\"lead\">链接不存在、已过期、已撤销，或分享能力已关闭。</p>"
                + "</main></body></html>";
    }

    private static String style() {
        return "<style>html{color-scheme:light;font-family:system-ui,-apple-system,sans-serif;background:#f5f7fa;color:#182230}"
                + "body{margin:0;padding:32px 16px}main{max-width:760px;margin:auto;background:#fff;border:1px solid #e4e7ec;border-radius:16px;padding:28px;box-shadow:0 8px 30px #10182814}"
                + ".badge{display:inline-block;color:#b42318;background:#fef3f2;padding:5px 9px;border-radius:999px;font-size:12px;font-weight:700}"
                + "h1{font-size:24px;margin:16px 0 6px}.lead{color:#667085;margin:0 0 22px}.grid{border-top:1px solid #eaecf0}"
                + ".row{display:grid;grid-template-columns:160px 1fr;gap:16px;padding:11px 0;border-bottom:1px solid #eaecf0}.key{color:#667085}.value{font-family:ui-monospace,monospace;overflow-wrap:anywhere}"
                + ".diagnostic{margin-top:20px;padding:14px;background:#f9fafb;border-radius:10px;white-space:pre-wrap;overflow-wrap:anywhere}footer{margin-top:24px;color:#98a2b3;font-size:12px}"
                + "@media(max-width:560px){main{padding:20px}.row{grid-template-columns:1fr;gap:4px}}</style>";
    }

    private static String grid(String rows) {
        return "<section class=\"grid\">" + rows + "</section>";
    }

    private static String row(String key, String value) {
        if (value == null || value.isBlank()) return "";
        return "<div class=\"row\"><div class=\"key\">" + esc(key) + "</div><div class=\"value\">"
                + esc(value) + "</div></div>";
    }

    private static String diagnosticText(String value) {
        if (value == null || value.isBlank()) return "";
        return "<section class=\"diagnostic\"><strong>安全诊断文本</strong><br>" + esc(value) + "</section>";
    }

    private static String join(String left, String right) {
        if (left == null) return right;
        if (right == null) return left;
        return left + " / " + right;
    }

    private static String format(java.time.LocalDateTime value) {
        return value != null ? value.format(DATE_TIME) : null;
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
