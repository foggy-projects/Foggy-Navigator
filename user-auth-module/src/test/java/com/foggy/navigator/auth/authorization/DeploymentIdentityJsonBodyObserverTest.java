package com.foggy.navigator.auth.authorization;

import com.foggy.navigator.common.authorization.AuthorizationContextV1;
import com.foggy.navigator.common.authorization.AuthorizationDecisionOutcome;
import com.foggy.navigator.common.authorization.AuthorizationReasonCode;
import com.foggy.navigator.common.authorization.AuthorizationRouteCatalog;
import com.foggy.navigator.common.authorization.AuthorizationShadowEvaluator;
import com.foggy.navigator.common.authorization.DeploymentIdentity;
import com.foggy.navigator.common.authorization.DeploymentIdentityProvider;
import com.foggy.navigator.common.authorization.DeploymentIdentitySource;
import com.foggy.navigator.common.authorization.PolicyDecisionV1;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentIdentityJsonBodyObserverTest {

    private final DeploymentIdentityJsonBodyObserver observer = new DeploymentIdentityJsonBodyObserver();
    private final AuthorizationRouteCatalog routeCatalog = new AuthorizationRouteCatalog();
    private final LegacyAuthorizationContextAdapter contextAdapter = new LegacyAuthorizationContextAdapter(
            deploymentIdentityProvider(), routeCatalog);
    private final AuthorizationShadowEvaluator shadowEvaluator = new AuthorizationShadowEvaluator(routeCatalog);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new EchoBodyController())
            .setControllerAdvice(observer)
            .build();

    @Test
    void observesOnlyJsonIdentityFieldNamesAndLeavesLegacyBodyAndStatusUntouched() throws Exception {
        String body = "{\"navigator_instance_id\":\"opaque-body-instance\","
                + "\"environment-profile\":\"opaque-body-profile\","
                + "\"payload\":\"legacy-body-value\"}";

        MvcResult result = performJsonEcho(body);

        assertEquals(HttpStatus.ACCEPTED.value(), result.getResponse().getStatus());
        assertEquals(body, result.getResponse().getContentAsString());
        MockHttpServletRequest request = result.getRequest();
        assertEquals(Boolean.TRUE, request.getAttribute(
                LegacyAuthorizationContextAdapter.DEPLOYMENT_IDENTITY_OVERRIDE_ATTRIBUTE));

        AuthorizationContextV1 context = contextAdapter.adapt(request);
        PolicyDecisionV1 decision = shadowEvaluator.evaluate(context);
        assertTrue(context.deploymentIdentityOverrideAttempt());
        assertEquals("server-owned-instance", context.deployment().navigatorInstanceId());
        assertEquals("test", context.deployment().environmentProfile());
        assertFalse(context.toString().contains("opaque-body-instance"));
        assertFalse(context.toString().contains("opaque-body-profile"));
        assertEquals(AuthorizationDecisionOutcome.DENY, decision.decision());
        assertEquals(AuthorizationReasonCode.AUTHZ_DEPLOYMENT_IDENTITY_OVERRIDE, decision.reasonCode());
    }

    @Test
    void leavesOrdinaryJsonValuesUntouchedWhenTheyOnlyMentionAnIdentityKey() throws Exception {
        String body = "{\"message\":\"navigatorInstanceId=opaque-body-instance\","
                + "\"payload\":\"legacy-body-value\"}";

        MvcResult result = performJsonEcho(body);

        assertEquals(HttpStatus.ACCEPTED.value(), result.getResponse().getStatus());
        assertEquals(body, result.getResponse().getContentAsString());
        assertNull(result.getRequest().getAttribute(
                LegacyAuthorizationContextAdapter.DEPLOYMENT_IDENTITY_OVERRIDE_ATTRIBUTE));
        assertFalse(contextAdapter.adapt(result.getRequest()).deploymentIdentityOverrideAttempt());
    }

    @Test
    void leavesNonJsonBodiesOutsideTheObserver() throws Exception {
        ByteArrayHttpInputMessage inputMessage = new ByteArrayHttpInputMessage(
                MediaType.TEXT_PLAIN, "navigatorInstanceId=opaque-body-instance");

        HttpInputMessage observed = observer.beforeBodyRead(inputMessage, null, String.class,
                StringHttpMessageConverter.class);

        assertSame(inputMessage, observed);
        assertEquals("navigatorInstanceId=opaque-body-instance",
                new String(observed.getBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private MvcResult performJsonEcho(String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static DeploymentIdentityProvider deploymentIdentityProvider() {
        return () -> new DeploymentIdentity("server-owned-instance", "test",
                DeploymentIdentitySource.CONFIGURED, false);
    }

    @RestController
    private static final class EchoBodyController {

        @PostMapping(value = "/api/v1/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
        private ResponseEntity<String> echo(@RequestBody String body) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }
    }

    private static final class ByteArrayHttpInputMessage implements HttpInputMessage {

        private final HttpHeaders headers = new HttpHeaders();
        private final InputStream body;

        private ByteArrayHttpInputMessage(MediaType contentType, String body) {
            headers.setContentType(contentType);
            this.body = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() throws IOException {
            return body;
        }
    }
}
