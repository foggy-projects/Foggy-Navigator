package com.foggy.navigator.claude.worker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformSkillDeployerTest {

    private static final String RETIRED_SKILL = """
            ---
            name: cross-project-task
            ---

            /api/v1/cross-project-tasks
            """;

    @TempDir
    Path userHome;

    @Test
    void retireSkills_removesManagedFilesFromAllLegacyPaths() throws IOException {
        List<Path> skillDirs = List.of(
                userHome.resolve(".agents/skills/cross-project-task"),
                userHome.resolve(".agent/skills/cross-project-task"),
                userHome.resolve(".claude/skills/cross-project-task")
        );
        for (Path skillDir : skillDirs) {
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), RETIRED_SKILL, StandardCharsets.UTF_8);
        }
        Files.writeString(skillDirs.get(0).resolve("notes.txt"), "keep", StandardCharsets.UTF_8);

        PlatformSkillDeployer deployer = new PlatformSkillDeployer();
        deployer.retireSkills(userHome);
        deployer.retireSkills(userHome);

        for (Path skillDir : skillDirs) {
            assertFalse(Files.exists(skillDir.resolve("SKILL.md")));
        }
        assertTrue(Files.exists(skillDirs.get(0).resolve("notes.txt")));
        assertFalse(Files.exists(skillDirs.get(1)));
        assertFalse(Files.exists(skillDirs.get(2)));
    }

    @Test
    void retireSkills_preservesUnrecognizedSkill() throws IOException {
        Path skillFile = userHome.resolve(".agents/skills/cross-project-task/SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, "name: user-managed-skill", StandardCharsets.UTF_8);

        new PlatformSkillDeployer().retireSkills(userHome);

        assertTrue(Files.exists(skillFile));
    }

    @Test
    void askAgentTemplate_isRestrictedToScheduledA2a() throws IOException {
        ClassPathResource askAgent = new ClassPathResource("platform-skills/ask-agent/SKILL.md.template");
        String content;
        try (var input = askAgent.getInputStream()) {
            content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(content.contains("[NAVIGATOR_SCHEDULED_A2A]"));
        assertTrue(content.contains("targetAgentId: agent-xxx"));
        assertTrue(content.contains("TARGET_AGENT_ID"));
        assertTrue(content.contains("不得使用此 Skill"));
        assertFalse(content.contains("{{AGENT_TABLE}}"));
        assertFalse(content.contains("targetAgentName"));
        assertFalse(content.contains("agentId-or-agentName"));
        assertFalse(content.contains("@foggy-api"));
    }

    @Test
    void crossProjectSkill_isNoLongerBundled() {
        ClassPathResource retired = new ClassPathResource("platform-skills/cross-project-task/SKILL.md");

        assertFalse(retired.exists());
    }
}
