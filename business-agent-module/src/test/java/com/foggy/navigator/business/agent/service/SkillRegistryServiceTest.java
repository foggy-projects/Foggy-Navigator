package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ClientAppSkillGrantDTO;
import com.foggy.navigator.business.agent.model.dto.SkillDTO;
import com.foggy.navigator.business.agent.model.dto.SkillFunctionAllowlistDTO;
import com.foggy.navigator.business.agent.model.dto.SkillMaterializeResultDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionVersionEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppFunctionGrantEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppSkillGrantEntity;
import com.foggy.navigator.business.agent.model.entity.SkillBundleEntity;
import com.foggy.navigator.business.agent.model.entity.SkillEntity;
import com.foggy.navigator.business.agent.model.entity.SkillFunctionAllowlistEntity;
import com.foggy.navigator.business.agent.model.form.AddFunctionToSkillForm;
import com.foggy.navigator.business.agent.model.form.CreateSkillForm;
import com.foggy.navigator.business.agent.model.form.GrantSkillToClientAppForm;
import com.foggy.navigator.business.agent.model.form.SkillBundleFunctionForm;
import com.foggy.navigator.business.agent.model.form.SkillResourceForm;
import com.foggy.navigator.business.agent.model.form.SyncAccountSkillBundleForm;
import com.foggy.navigator.business.agent.model.form.SyncSkillBundleForm;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRepository;
import com.foggy.navigator.business.agent.repository.BusinessFunctionVersionRepository;
import com.foggy.navigator.business.agent.repository.ClientAppFunctionGrantRepository;
import com.foggy.navigator.business.agent.repository.ClientAppSkillGrantRepository;
import com.foggy.navigator.business.agent.repository.SkillBundleRepository;
import com.foggy.navigator.business.agent.repository.SkillFunctionAllowlistRepository;
import com.foggy.navigator.business.agent.repository.SkillRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillRegistryServiceTest {

    @Mock
    private SkillRepository skillRepository;
    @Mock
    private SkillBundleRepository skillBundleRepository;
    @Mock
    private SkillFunctionAllowlistRepository allowlistRepository;
    @Mock
    private ClientAppSkillGrantRepository grantRepository;
    @Mock
    private ClientAppFunctionGrantRepository functionGrantRepository;
    @Mock
    private BusinessFunctionRepository functionRepository;
    @Mock
    private BusinessFunctionVersionRepository versionRepository;
    @Mock
    private ClientAppService clientAppService;
    @Mock
    private ClientAppUserGrantService userGrantService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private SkillRegistryService skillRegistryService;

    @Test
    void createSkill_success() {
        CreateSkillForm form = new CreateSkillForm();
        form.setSkillId("skill_01");
        form.setName("Test Skill");
        SkillResourceForm resource = new SkillResourceForm();
        resource.setPath("references/usage.md");
        resource.setContent("Use this when needed.");
        form.setResources(List.of(resource));

        when(skillRepository.findByTenantIdAndSkillId("tenant_1", "skill_01")).thenReturn(Optional.empty());
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SkillDTO dto = skillRegistryService.createSkill("tenant_1", "user_1", form);

        assertNotNull(dto);
        assertEquals("skill_01", dto.getSkillId());
        assertEquals("Test Skill", dto.getName());
        assertEquals("ENABLED", dto.getStatus());
        verify(skillRepository).save(argThat(entity ->
                entity.getResourcesJson() != null
                        && entity.getResourcesJson().contains("references/usage.md")
                        && entity.getResourcesJson().contains("Use this when needed.")));
    }

    @Test
    void addFunctionToSkillAllowlist_success() {
        AddFunctionToSkillForm form = new AddFunctionToSkillForm();
        form.setFunctionId("func_01");

        SkillEntity skill = new SkillEntity();
        skill.setStatus("ENABLED");
        when(skillRepository.findByTenantIdAndSkillId("tenant_1", "skill_01")).thenReturn(Optional.of(skill));

        BusinessFunctionEntity func = new BusinessFunctionEntity();
        func.setStatus("ENABLED");
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.of(func));

        when(allowlistRepository.findByTenantIdAndSkillIdAndFunctionId("tenant_1", "skill_01", "func_01")).thenReturn(Optional.empty());
        when(allowlistRepository.save(any(SkillFunctionAllowlistEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SkillFunctionAllowlistDTO dto = skillRegistryService.addFunctionToSkillAllowlist("tenant_1", "skill_01", "user_1", form);

        assertNotNull(dto);
        assertEquals("func_01", dto.getFunctionId());
    }

    @Test
    void checkClientAppSkillAccess_throwsIfDisabled() {
        when(clientAppService.requireActiveClientApp(anyString(), anyString())).thenReturn(new ClientAppEntity());

        SkillEntity skill = new SkillEntity();
        skill.setStatus("DISABLED");
        when(skillRepository.findByTenantIdAndSkillId("tenant_1", "skill_01")).thenReturn(Optional.of(skill));

        assertThrows(IllegalStateException.class, () -> {
            skillRegistryService.checkClientAppSkillAccess("tenant_1", "app_01", "skill_01");
        });
    }

    @Test
    void checkSkillFunctionAccess_success() {
        SkillEntity skill = new SkillEntity();
        skill.setStatus("ENABLED");
        when(skillRepository.findByTenantIdAndSkillId("tenant_1", "skill_01")).thenReturn(Optional.of(skill));

        SkillFunctionAllowlistEntity allowlist = new SkillFunctionAllowlistEntity();
        allowlist.setStatus("ENABLED");
        when(allowlistRepository.findByTenantIdAndSkillIdAndFunctionId("tenant_1", "skill_01", "func_01")).thenReturn(Optional.of(allowlist));

        assertDoesNotThrow(() -> {
            skillRegistryService.checkSkillFunctionAccess("tenant_1", "skill_01", "func_01");
        });
    }

    @Test
    void materializePublicSkill_postsToWorkerAndIncludesAllowlistedFunctions() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/skills/materialize", exchange -> {
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"success\",\"path\":\"/tmp/SKILL.md\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        try {
            ReflectionTestUtils.setField(skillRegistryService, "devSyncWorkerUrl", "http://localhost:" + server.getAddress().getPort());

            SkillEntity skill = new SkillEntity();
            skill.setTenantId("tenant_1");
            skill.setSkillId("tms_skill");
            skill.setName("TMS Skill");
            skill.setDescription("TMS public skill");
            skill.setMarkdownBody("Use this skill for TMS operations.");
            skill.setResourcesJson("[{\"path\":\"references/usage.md\",\"content\":\"Use this reference.\"}]");
            skill.setStatus("ENABLED");
            when(skillRepository.findByTenantIdAndSkillId("tenant_1", "tms_skill")).thenReturn(Optional.of(skill));

            SkillFunctionAllowlistEntity allow = new SkillFunctionAllowlistEntity();
            allow.setFunctionId("tms.order.submit");
            allow.setStatus("ENABLED");
            when(allowlistRepository.findByTenantIdAndSkillId("tenant_1", "tms_skill")).thenReturn(List.of(allow));

            BusinessFunctionEntity function = new BusinessFunctionEntity();
            function.setFunctionId("tms.order.submit");
            function.setCurrentVersion("v1");
            function.setName("Submit TMS Order");
            function.setDescription("Submit order");
            function.setStatus("ENABLED");
            when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "tms.order.submit")).thenReturn(Optional.of(function));

            BusinessFunctionVersionEntity version = new BusinessFunctionVersionEntity();
            version.setStatus("ENABLED");
            version.setLlmVisibleSummary("Submit by orderIdentifier.");
            when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "tms.order.submit", "v1")).thenReturn(Optional.of(version));

            SkillMaterializeResultDTO result = skillRegistryService.materializePublicSkill("tenant_1", "tms_skill");

            assertEquals("MATERIALIZED", result.getStatus());
            assertEquals(200, result.getWorkerStatusCode());
            assertNotNull(bodyRef.get());
            assertTrue(bodyRef.get().contains("\"skill_id\":\"tms_skill\""));
            assertTrue(bodyRef.get().contains("\"name\":\"tms_skill\""));
            assertTrue(bodyRef.get().contains("\"display_name\":\"TMS Skill\""));
            assertTrue(bodyRef.get().contains("\"resources\":[{\"path\":\"references/usage.md\",\"content\":\"Use this reference.\"}]"));
            assertTrue(bodyRef.get().contains("tms.order.submit@v1"));
            assertTrue(bodyRef.get().contains("Submit by orderIdentifier."));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void addFunctionToSkillAllowlist_autoMaterializesGrantedClientAppPublicSkill() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/skills/materialize", exchange -> {
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        try {
            ReflectionTestUtils.setField(skillRegistryService, "devSyncWorkerUrl", "http://localhost:" + server.getAddress().getPort());

            AddFunctionToSkillForm form = new AddFunctionToSkillForm();
            form.setFunctionId("tms.order.submit");

            SkillEntity skill = new SkillEntity();
            skill.setTenantId("tenant_1");
            skill.setSkillId("tms_skill");
            skill.setName("TMS Skill");
            skill.setStatus("ENABLED");
            when(skillRepository.findByTenantIdAndSkillId("tenant_1", "tms_skill")).thenReturn(Optional.of(skill));

            BusinessFunctionEntity function = new BusinessFunctionEntity();
            function.setFunctionId("tms.order.submit");
            function.setCurrentVersion("v1");
            function.setName("Submit TMS Order");
            function.setStatus("ENABLED");
            when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "tms.order.submit")).thenReturn(Optional.of(function));

            SkillFunctionAllowlistEntity allow = new SkillFunctionAllowlistEntity();
            allow.setFunctionId("tms.order.submit");
            allow.setStatus("ENABLED");
            when(allowlistRepository.findByTenantIdAndSkillIdAndFunctionId("tenant_1", "tms_skill", "tms.order.submit")).thenReturn(Optional.empty());
            when(allowlistRepository.save(any(SkillFunctionAllowlistEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(allowlistRepository.findByTenantIdAndSkillId("tenant_1", "tms_skill")).thenReturn(List.of(allow));

            ClientAppSkillGrantEntity grant = new ClientAppSkillGrantEntity();
            grant.setClientAppId("tms_app");
            grant.setSkillId("tms_skill");
            grant.setStatus("ENABLED");
            when(grantRepository.findByTenantIdAndSkillId("tenant_1", "tms_skill")).thenReturn(List.of(grant));

            BusinessFunctionVersionEntity version = new BusinessFunctionVersionEntity();
            version.setStatus("ENABLED");
            version.setLlmVisibleSummary("Submit by orderIdentifier.");
            when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "tms.order.submit", "v1")).thenReturn(Optional.of(version));

            skillRegistryService.addFunctionToSkillAllowlist("tenant_1", "tms_skill", "user_1", form);

            assertNotNull(bodyRef.get());
            assertTrue(bodyRef.get().contains("\"skill_id\":\"tms_skill\""));
            assertTrue(bodyRef.get().contains("\"client_app_id\":\"tms_app\""));
            assertTrue(bodyRef.get().contains("tms.order.submit@v1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void grantSkillToClientApp_autoMaterializesClientAppPublicSkill() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/skills/materialize", exchange -> {
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        try {
            ReflectionTestUtils.setField(skillRegistryService, "devSyncWorkerUrl", "http://localhost:" + server.getAddress().getPort());

            GrantSkillToClientAppForm form = new GrantSkillToClientAppForm();
            form.setSkillId("tms_skill");

            when(clientAppService.requireActiveClientApp("tenant_1", "tms_app")).thenReturn(new ClientAppEntity());

            SkillEntity skill = new SkillEntity();
            skill.setTenantId("tenant_1");
            skill.setSkillId("tms_skill");
            skill.setName("TMS Skill");
            skill.setStatus("ENABLED");
            when(skillRepository.findByTenantIdAndSkillId("tenant_1", "tms_skill")).thenReturn(Optional.of(skill));

            when(grantRepository.findByTenantIdAndClientAppIdAndSkillId("tenant_1", "tms_app", "tms_skill")).thenReturn(Optional.empty());
            when(grantRepository.save(any(ClientAppSkillGrantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(allowlistRepository.findByTenantIdAndSkillId("tenant_1", "tms_skill")).thenReturn(List.of());

            ClientAppSkillGrantDTO dto = skillRegistryService.grantSkillToClientApp("tenant_1", "tms_app", "user_1", form);

            assertEquals("tms_app", dto.getClientAppId());
            assertNotNull(bodyRef.get());
            assertTrue(bodyRef.get().contains("\"skill_id\":\"tms_skill\""));
            assertTrue(bodyRef.get().contains("\"client_app_id\":\"tms_app\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void syncSkillBundle_publicMaterializesAndBackfillsLegacyIndexes() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/skills/materialize", exchange -> {
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        try {
            ReflectionTestUtils.setField(skillRegistryService, "devSyncWorkerUrl", "http://localhost:" + server.getAddress().getPort());

            SyncSkillBundleForm form = new SyncSkillBundleForm();
            form.setClientAppId("tms_app");
            form.setScope("client-app-public");
            form.setSkillId("tms_skill");
            form.setName("TMS Skill");
            form.setMarkdownBody("Use this skill for TMS.");
            form.setMaterialize(true);
            SkillBundleFunctionForm functionForm = new SkillBundleFunctionForm();
            functionForm.setFunctionId("tms.order.submit");
            form.setFunctions(List.of(functionForm));

            when(clientAppService.requireActiveClientApp("tenant_1", "tms_app")).thenReturn(new ClientAppEntity());
            BusinessFunctionEntity function = new BusinessFunctionEntity();
            function.setFunctionId("tms.order.submit");
            function.setName("Submit Order");
            function.setStatus("ENABLED");
            when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "tms.order.submit")).thenReturn(Optional.of(function));
            ClientAppFunctionGrantEntity functionGrant = new ClientAppFunctionGrantEntity();
            functionGrant.setFunctionId("tms.order.submit");
            functionGrant.setStatus("ENABLED");
            when(functionGrantRepository.findByTenantIdAndClientAppId("tenant_1", "tms_app")).thenReturn(List.of(functionGrant));
            when(skillBundleRepository.findByTenantIdAndClientAppIdAndScopeAndAccountIdAndSkillId(
                    "tenant_1", "tms_app", "CLIENT_APP_PUBLIC", "", "tms_skill")).thenReturn(Optional.empty());
            when(skillBundleRepository.save(any(SkillBundleEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(skillRepository.findByTenantIdAndSkillId("tenant_1", "tms_skill")).thenReturn(Optional.empty());
            when(skillRepository.save(any(SkillEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(grantRepository.findByTenantIdAndClientAppIdAndSkillId("tenant_1", "tms_app", "tms_skill")).thenReturn(Optional.empty());
            when(grantRepository.save(any(ClientAppSkillGrantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(allowlistRepository.findByTenantIdAndSkillIdAndFunctionId("tenant_1", "tms_skill", "tms.order.submit")).thenReturn(Optional.empty());
            when(allowlistRepository.save(any(SkillFunctionAllowlistEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            var dto = skillRegistryService.syncSkillBundle("tenant_1", "user_1", form);

            assertEquals("CLIENT_APP_PUBLIC", dto.getScope());
            assertEquals("MATERIALIZED", dto.getMaterializeResult().getStatus());
            assertNotNull(bodyRef.get());
            assertTrue(bodyRef.get().contains("\"scope\":\"public\""));
            assertTrue(bodyRef.get().contains("\"client_app_id\":\"tms_app\""));
            assertTrue(bodyRef.get().contains("tms.order.submit"));
            verify(skillRepository).save(any(SkillEntity.class));
            verify(grantRepository).save(any(ClientAppSkillGrantEntity.class));
            verify(allowlistRepository).save(any(SkillFunctionAllowlistEntity.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void syncMyAccountSkillBundle_materializesAccountScope() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/skills/materialize", exchange -> {
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        try {
            ReflectionTestUtils.setField(skillRegistryService, "devSyncWorkerUrl", "http://localhost:" + server.getAddress().getPort());

            SyncAccountSkillBundleForm form = new SyncAccountSkillBundleForm();
            form.setSkillId("personal_skill");
            form.setName("Personal Skill");
            form.setMarkdownBody("Use this only for this account.");
            form.setMaterialize(true);

            when(clientAppService.requireActiveClientApp("tenant_1", "tms_app")).thenReturn(new ClientAppEntity());
            when(skillBundleRepository.findByTenantIdAndClientAppIdAndScopeAndAccountIdAndSkillId(
                    "tenant_1", "tms_app", "ACCOUNT_PRIVATE", "staff_1", "personal_skill")).thenReturn(Optional.empty());
            when(skillBundleRepository.save(any(SkillBundleEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(skillRepository.findByTenantIdAndSkillId("tenant_1", "personal_skill")).thenReturn(Optional.empty());
            when(skillRepository.save(any(SkillEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(grantRepository.findByTenantIdAndClientAppIdAndSkillId("tenant_1", "tms_app", "personal_skill")).thenReturn(Optional.empty());
            when(grantRepository.save(any(ClientAppSkillGrantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            var dto = skillRegistryService.syncMyAccountSkillBundle("tenant_1", "tms_app", "staff_1", form);

            assertEquals("ACCOUNT_PRIVATE", dto.getScope());
            assertEquals("staff_1", dto.getAccountId());
            assertEquals("MATERIALIZED", dto.getMaterializeResult().getStatus());
            assertNotNull(bodyRef.get());
            assertTrue(bodyRef.get().contains("\"scope\":\"account\""));
            assertTrue(bodyRef.get().contains("\"account_id\":\"staff_1\""));
            verify(userGrantService).checkUpstreamUserAccess("tenant_1", "tms_app", "staff_1");
        } finally {
            server.stop(0);
        }
    }
}
