package com.foggy.navigator.auth.interceptor;

import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Observer-only bridge from the legacy {@link RX} envelope to the P1A shadow
 * audit. Existing endpoints often keep HTTP 200 for a business failure, so
 * HTTP status alone cannot truthfully represent the legacy result. The body
 * object is returned unchanged; no message, payload, credential, or request
 * content is retained.
 */
@RestControllerAdvice
public class AuthorizationShadowResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        // ResponseEntity<RX<?>> exposes RX only after return-type selection.
        // Inspecting the runtime body is therefore necessary and remains
        // observer-only for non-RX responses.
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        try {
            if (body instanceof RX<?> envelope && request instanceof ServletServerHttpRequest servletRequest) {
                HttpServletRequest servletRequestDelegate = servletRequest.getServletRequest();
                AuthorizationShadowInterceptor.recordLegacyRxOutcome(
                        servletRequestDelegate,
                        envelope.isOk());
            }
        } catch (RuntimeException ignored) {
            // The observer must never alter a legacy body, status, or side effect.
        }
        return body;
    }
}
