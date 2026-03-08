package com.foggy.navigator.auth.config;

import com.foggyframework.core.ex.ExRuntimeException;
import com.foggyframework.core.ex.ExRuntimeExceptionImpl;
import com.foggyframework.core.ex.RX;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * 将业务异常和安全异常映射为正确的 HTTP 状态码
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理 RX.throwA/B/C 抛出的业务异常
     */
    @ExceptionHandler(ExRuntimeExceptionImpl.class)
    public ResponseEntity<RX<?>> handleExRuntimeException(ExRuntimeExceptionImpl ex) {
        ExRuntimeException exr = ex;
        log.warn("Business exception: code={}, exCode={}, message={}",
                exr.getCode(), exr.getExCode(), exr.getMessage());

        RX<?> rx = exr.toR();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(rx);
    }

    /**
     * 处理认证/授权异常（来自 AuthAspect）
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<RX<?>> handleSecurityException(SecurityException ex) {
        log.warn("Security exception: {}", ex.getMessage());

        RX<?> rx = RX.error(ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(rx);
    }

    /**
     * 处理参数校验异常（如 Service 层的 IllegalArgumentException）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RX<?>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Validation error: {}", ex.getMessage());

        RX<?> rx = RX.error(ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(rx);
    }
}
