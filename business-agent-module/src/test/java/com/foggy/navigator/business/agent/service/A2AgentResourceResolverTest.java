package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.service.A2AgentResourceResolver.ResolvedModelResource;
import com.foggy.navigator.common.enums.LlmModelCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2AgentResourceResolverTest {

    private ClientAppModelConfigGrantService modelConfigGrantService;
    private A2AgentResourceResolver resolver;

    @BeforeEach
    void setUp() {
        modelConfigGrantService = mock(ClientAppModelConfigGrantService.class);
        resolver = new A2AgentResourceResolver(modelConfigGrantService);
    }

    @Test
    void resolveRequiredModel_reports_requested_model_grant_source() {
        when(modelConfigGrantService.resolveEffectiveModelConfigId(
                "tenant-1", "capp-1", "cfg-requested", LlmModelCategory.GENERAL))
                .thenReturn("cfg-requested");

        ResolvedModelResource result = resolver.resolveRequiredModel(
                "tenant-1", "capp-1", " cfg-requested ", LlmModelCategory.GENERAL);

        assertEquals("cfg-requested", result.modelConfigId());
        assertEquals("cfg-requested", result.requestedModelConfigId());
        assertEquals(LlmModelCategory.GENERAL, result.category());
        assertEquals("REQUESTED_MODEL_GRANT", result.source());
    }

    @Test
    void resolveRequiredModel_reports_default_model_grant_source() {
        when(modelConfigGrantService.resolveEffectiveModelConfigId(
                "tenant-1", "capp-1", null, LlmModelCategory.GENERAL))
                .thenReturn("cfg-default");

        ResolvedModelResource result = resolver.resolveRequiredModel(
                "tenant-1", "capp-1", " ", LlmModelCategory.GENERAL);

        assertEquals("cfg-default", result.modelConfigId());
        assertNull(result.requestedModelConfigId());
        assertEquals("DEFAULT_MODEL_GRANT", result.source());
    }

    @Test
    void resolveOptionalModel_returns_empty_without_default_grant() {
        when(modelConfigGrantService.tryResolveEffectiveModelConfigId(
                "tenant-1", "capp-1", null, LlmModelCategory.VISION))
                .thenReturn(Optional.empty());

        Optional<ResolvedModelResource> result = resolver.resolveOptionalModel(
                "tenant-1", "capp-1", LlmModelCategory.VISION);

        assertTrue(result.isEmpty());
        verify(modelConfigGrantService).tryResolveEffectiveModelConfigId(
                "tenant-1", "capp-1", null, LlmModelCategory.VISION);
    }
}
