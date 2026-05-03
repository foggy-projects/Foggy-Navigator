package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppSkillGrantDTO;
import com.foggy.navigator.business.agent.model.dto.SkillDTO;
import com.foggy.navigator.business.agent.model.dto.SkillFunctionAllowlistDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppSkillGrantEntity;
import com.foggy.navigator.business.agent.model.entity.SkillEntity;
import com.foggy.navigator.business.agent.model.entity.SkillFunctionAllowlistEntity;
import com.foggy.navigator.business.agent.model.form.AddFunctionToSkillForm;
import com.foggy.navigator.business.agent.model.form.CreateSkillForm;
import com.foggy.navigator.business.agent.model.form.GrantSkillToClientAppForm;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRepository;
import com.foggy.navigator.business.agent.repository.ClientAppSkillGrantRepository;
import com.foggy.navigator.business.agent.repository.SkillFunctionAllowlistRepository;
import com.foggy.navigator.business.agent.repository.SkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillRegistryServiceTest {

    @Mock
    private SkillRepository skillRepository;
    @Mock
    private SkillFunctionAllowlistRepository allowlistRepository;
    @Mock
    private ClientAppSkillGrantRepository grantRepository;
    @Mock
    private BusinessFunctionRepository functionRepository;
    @Mock
    private ClientAppService clientAppService;

    @InjectMocks
    private SkillRegistryService skillRegistryService;

    @Test
    void createSkill_success() {
        CreateSkillForm form = new CreateSkillForm();
        form.setSkillId("skill_01");
        form.setName("Test Skill");

        when(skillRepository.findByTenantIdAndSkillId("tenant_1", "skill_01")).thenReturn(Optional.empty());
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SkillDTO dto = skillRegistryService.createSkill("tenant_1", "user_1", form);

        assertNotNull(dto);
        assertEquals("skill_01", dto.getSkillId());
        assertEquals("Test Skill", dto.getName());
        assertEquals("ENABLED", dto.getStatus());
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
}
