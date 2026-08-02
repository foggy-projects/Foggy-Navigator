package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.service.CrossProjectMutationRetiredException;
import com.foggyframework.core.ex.RX;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HTTP compatibility response for authenticated callers of retired mutations.
 */
@RestControllerAdvice(assignableTypes = CrossProjectTaskController.class)
public class CrossProjectTaskRetirementAdvice {

    @ExceptionHandler(CrossProjectMutationRetiredException.class)
    public ResponseEntity<RX<?>> handleRetiredMutation(CrossProjectMutationRetiredException exception) {
        return ResponseEntity.status(HttpStatus.GONE)
                .cacheControl(CacheControl.noStore())
                .body(RX.failB(exception.getMessage()));
    }
}
