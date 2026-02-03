package com.foggy.navigator.tutor.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.tutor.config.TutorAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodingAgentToolExecutorTest {

    private TutorAgentProperties properties;
    private ObjectMapper objectMapper;
    private CodingAgentToolExecutor executor;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;

    @Mock
    private RestClient.RequestHeadersSpec<?> requestHeadersSpec;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        properties = new TutorAgentProperties();
        properties.setCodingAgentBaseUrl("http://localhost:8112");
        objectMapper = new ObjectMapper();

        // 使用真实的 ObjectMapper 但模拟 RestClient
        executor = new CodingAgentToolExecutor(properties, objectMapper);
    }

    @Test
    void testPropertiesDefaultValues() {
        TutorAgentProperties props = new TutorAgentProperties();

        assertEquals("http://localhost:8112", props.getCodingAgentBaseUrl());
        assertEquals("default", props.getDefaultTenantId());
        assertEquals(5000, props.getConnectTimeout());
        assertEquals(30000, props.getReadTimeout());
        assertTrue(props.isInitSkillsOnStartup());
        assertEquals("tutor-agent", props.getAgentId());
    }

    @Test
    void testObjectMapperParsesJsonArray() throws Exception {
        String json = "{\"data\": [{\"id\": \"1\", \"name\": \"test\"}]}";

        // 验证 ObjectMapper 可以正确解析 JSON
        var node = objectMapper.readTree(json);
        var dataNode = node.get("data");

        assertTrue(dataNode.isArray());
        assertEquals("1", dataNode.get(0).get("id").asText());
    }

    @Test
    void testObjectMapperParsesJsonObject() throws Exception {
        String json = "{\"data\": {\"id\": \"123\", \"status\": \"active\"}}";

        // 验证 ObjectMapper 可以正确解析 JSON
        var node = objectMapper.readTree(json);
        var dataNode = node.get("data");

        assertTrue(dataNode.isObject());
        assertEquals("123", dataNode.get("id").asText());
        assertEquals("active", dataNode.get("status").asText());
    }

    @Test
    void testPropertiesCanBeConfigured() {
        TutorAgentProperties props = new TutorAgentProperties();

        props.setCodingAgentBaseUrl("http://custom:9000");
        props.setDefaultTenantId("custom-tenant");
        props.setConnectTimeout(10000);
        props.setReadTimeout(60000);
        props.setInitSkillsOnStartup(false);
        props.setAgentId("custom-agent");

        assertEquals("http://custom:9000", props.getCodingAgentBaseUrl());
        assertEquals("custom-tenant", props.getDefaultTenantId());
        assertEquals(10000, props.getConnectTimeout());
        assertEquals(60000, props.getReadTimeout());
        assertFalse(props.isInitSkillsOnStartup());
        assertEquals("custom-agent", props.getAgentId());
    }
}
