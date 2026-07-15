package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BizWorkerCredentialDTO;
import com.foggy.navigator.business.agent.model.form.RotateWorkerCredentialForm;
import com.foggy.navigator.business.agent.service.BizWorkerCredentialService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BizWorkerCredentialControllerTest {

    @Test
    void controllerRequiresSuperAdmin() {
        RequireAuth guard = BizWorkerCredentialController.class.getAnnotation(RequireAuth.class);

        assertNotNull(guard);
        assertTrue(Arrays.asList(guard.roles()).contains("SUPER_ADMIN"));
    }

    @Test
    void rotateUsesPlatformOwnerCredentialPath() {
        BizWorkerCredentialService service = mock(BizWorkerCredentialService.class);
        BizWorkerCredentialController controller = new BizWorkerCredentialController(service);
        RotateWorkerCredentialForm form = new RotateWorkerCredentialForm();
        form.setTtlSeconds(3600L);
        BizWorkerCredentialDTO expected = new BizWorkerCredentialDTO();
        expected.setWorkerId("worker-1");
        expected.setSecret("bwc_once");
        when(service.rotatePlatformCredential("worker-1", 3600L)).thenReturn(expected);
        MockHttpServletResponse response = new MockHttpServletResponse();

        RX<BizWorkerCredentialDTO> result = controller.rotate(response, "worker-1", form);

        assertEquals("bwc_once", result.getData().getSecret());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        verify(service).rotatePlatformCredential("worker-1", 3600L);
    }

    @Test
    void revokeUsesPlatformOwnerCredentialPath() {
        BizWorkerCredentialService service = mock(BizWorkerCredentialService.class);
        BizWorkerCredentialController controller = new BizWorkerCredentialController(service);
        BizWorkerCredentialDTO expected = new BizWorkerCredentialDTO();
        expected.setWorkerId("worker-1");
        when(service.revokePlatformCredential("worker-1")).thenReturn(expected);

        RX<BizWorkerCredentialDTO> result = controller.revoke("worker-1");

        assertEquals("worker-1", result.getData().getWorkerId());
        verify(service).revokePlatformCredential("worker-1");
    }
}
