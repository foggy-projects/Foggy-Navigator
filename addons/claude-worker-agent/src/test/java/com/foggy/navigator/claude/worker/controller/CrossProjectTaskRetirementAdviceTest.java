package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.auth.aspect.AuthAspect;
import com.foggy.navigator.auth.config.GlobalExceptionHandler;
import com.foggy.navigator.claude.worker.service.CrossProjectMutationRetiredException;
import com.foggy.navigator.claude.worker.service.CrossProjectTaskService;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CrossProjectTaskRetirementAdviceTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void retiredMutationRouteMapsToGoneNoStoreAndStableRxEnvelope() throws Exception {
        CrossProjectTaskService taskService = mock(CrossProjectTaskService.class);
        when(taskService.startTask("user-1", "ctx-existing"))
                .thenThrow(new CrossProjectMutationRetiredException());
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .username("tester")
                .build());
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new CrossProjectTaskController(taskService))
                .setControllerAdvice(new CrossProjectTaskRetirementAdvice())
                .build();

        mockMvc.perform(post("/api/v1/cross-project-tasks/ctx-existing/start"))
                .andExpect(status().isGone())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(600))
                .andExpect(jsonPath("$.exCode").value("B600"))
                .andExpect(jsonPath("$.msg").value(
                        CrossProjectMutationRetiredException.REASON_CODE));
    }

    @Test
    void unauthenticatedMutationIsRejectedBeforeRetirementServiceGate() {
        CrossProjectTaskService taskService = mock(CrossProjectTaskService.class);
        CrossProjectTaskController target = new CrossProjectTaskController(taskService);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new AuthAspect());
        CrossProjectTaskController securedController = proxyFactory.getProxy();
        UserContext.clear();

        SecurityException authenticationFailure = assertThrows(
                SecurityException.class,
                () -> securedController.startTask("ctx-existing"));

        verifyNoInteractions(taskService);
        ResponseEntity<RX<?>> response = new GlobalExceptionHandler()
                .handleSecurityException(authenticationFailure);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
