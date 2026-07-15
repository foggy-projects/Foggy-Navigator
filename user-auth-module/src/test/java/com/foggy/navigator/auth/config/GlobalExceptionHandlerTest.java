package com.foggy.navigator.auth.config;

import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleExRuntimeException_preservesBusinessEnvelope() {
        ResponseEntity<RX<?>> response = handler.handleExRuntimeException(
                RX.throwB("operation failed"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(600, response.getBody().getCode());
        assertEquals("B600", response.getBody().getExCode());
        assertEquals("operation failed", response.getBody().getMsg());
    }

    @Test
    void handleSecurityException_returns401ForUnauthenticatedUser() {
        ResponseEntity<RX<?>> response = handler.handleSecurityException(
                new SecurityException("未登录，请先登录"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void handleSecurityException_returns401ForInvalidCredential() {
        ResponseEntity<RX<?>> response = handler.handleSecurityException(
                new SecurityException("invalid control-plane credential"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void handleSecurityException_returns403ForPermissionDeniedUser() {
        ResponseEntity<RX<?>> response = handler.handleSecurityException(
                new SecurityException("无权限访问此接口"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void handleSecurityException_returns403ForAuthorizedUserScopeMismatch() {
        ResponseEntity<RX<?>> response = handler.handleSecurityException(
                new SecurityException("Not authorized to update this sharing key"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
