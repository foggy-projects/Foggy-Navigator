package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.SkillArtifactSliceDTO;
import com.foggy.navigator.business.agent.model.dto.SkillArtifactTreeDTO;
import com.foggy.navigator.business.agent.model.entity.SkillEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillArtifactServiceTest {

    private SkillRegistryService skillRegistryService;
    private SkillArtifactService service;

    @BeforeEach
    void setUp() {
        skillRegistryService = mock(SkillRegistryService.class);
        service = new SkillArtifactService(skillRegistryService, new ObjectMapper());
    }

    @Test
    void tree_returnsSkillMarkdownAndResourcesWithoutPhysicalPaths() {
        SkillEntity skill = skill("第一行\n第二行", "[{\"path\":\"references/runtime.md\",\"content\":\"runtime doc\"}]");
        when(skillRegistryService.getSkill("tenant_1", "skill_1")).thenReturn(skill);

        SkillArtifactTreeDTO tree = service.tree("tenant_1", "capp_1", "skill_1");

        assertEquals("skill_1", tree.getSkillId());
        assertEquals(2, tree.getFiles().size());
        assertEquals("SKILL.md", tree.getFiles().get(0).getPath());
        assertEquals("references/runtime.md", tree.getFiles().get(1).getPath());
        assertFalse(tree.getFiles().get(1).getSliceUrl().contains(":\\"));
        assertTrue(tree.getFiles().get(1).getSliceUrl().contains("path=references%2Fruntime.md"));
        verify(skillRegistryService).checkClientAppSkillAccess("tenant_1", "capp_1", "skill_1");
    }

    @Test
    void slice_usesCodePointOffsetsSoChineseDoesNotGarbled() {
        SkillEntity skill = skill("甲乙丙丁戊\nnext", null);
        when(skillRegistryService.getSkill("tenant_1", "skill_1")).thenReturn(skill);

        SkillArtifactSliceDTO slice = service.slice(
                "tenant_1", "capp_1", "skill_1", "SKILL.md", 1, 2, 3);

        assertEquals("乙丙丁", slice.getContent());
        assertEquals(1, slice.getNextLine());
        assertEquals(5, slice.getNextColumn());
        assertTrue(slice.isTruncated());
    }

    @Test
    void slice_canContinueWithinVeryLongSingleLine() {
        SkillEntity skill = skill("abcdef", null);
        when(skillRegistryService.getSkill("tenant_1", "skill_1")).thenReturn(skill);

        SkillArtifactSliceDTO first = service.slice(
                "tenant_1", "capp_1", "skill_1", "SKILL.md", 1, 1, 4);
        SkillArtifactSliceDTO second = service.slice(
                "tenant_1", "capp_1", "skill_1", "SKILL.md",
                first.getNextLine(), first.getNextColumn(), 4);

        assertEquals("abcd", first.getContent());
        assertEquals(1, first.getNextLine());
        assertEquals(5, first.getNextColumn());
        assertEquals("ef", second.getContent());
        assertFalse(second.isTruncated());
    }

    @Test
    void slice_rejectsPathTraversal() {
        SkillEntity skill = skill("body", null);
        when(skillRegistryService.getSkill("tenant_1", "skill_1")).thenReturn(skill);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.slice("tenant_1", "capp_1", "skill_1", "../SKILL.md", 1, 1, 10));

        assertEquals("SKILL_ARTIFACT_PATH_INVALID", error.getMessage());
    }

    private SkillEntity skill(String markdownBody, String resourcesJson) {
        SkillEntity skill = new SkillEntity();
        skill.setTenantId("tenant_1");
        skill.setSkillId("skill_1");
        skill.setName("Skill 1");
        skill.setMarkdownBody(markdownBody);
        skill.setResourcesJson(resourcesJson);
        skill.setStatus("ENABLED");
        return skill;
    }
}
