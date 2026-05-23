package com.foggy.navigator.observer.bff;

import com.foggy.navigator.sdk.exception.NavigatorApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ObserverBffExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Object>> handleResponseStatus(ResponseStatusException exception) {
        int statusCode = exception.getStatusCode().value();
        return ResponseEntity.status(statusCode)
                .body(ApiResponse.error(statusCode, exception.getReason()));
    }

    @ExceptionHandler(NavigatorApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleNavigatorApi(NavigatorApiException exception) {
        int statusCode = exception.getStatusCode() >= 400 ? exception.getStatusCode() : 502;
        return ResponseEntity.status(statusCode)
                .body(ApiResponse.error(statusCode, exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, exception.getMessage()));
    }
}
