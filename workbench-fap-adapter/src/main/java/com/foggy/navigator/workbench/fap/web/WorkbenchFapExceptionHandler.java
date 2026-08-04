package com.foggy.navigator.workbench.fap.web;

import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.WorkbenchFapError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.foggy.navigator.workbench.fap.web")
public class WorkbenchFapExceptionHandler {

    @ExceptionHandler(WorkbenchFapException.class)
    ResponseEntity<WorkbenchFapError> handle(WorkbenchFapException error) {
        return ResponseEntity.status(error.status())
                .body(new WorkbenchFapError(
                        error.code(), error.getMessage(), error.retryable()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<WorkbenchFapError> handleInvalid(IllegalArgumentException error) {
        return ResponseEntity.unprocessableEntity()
                .body(new WorkbenchFapError(
                        "FAP_WORKBENCH_REQUEST_INVALID", error.getMessage(), false));
    }
}
