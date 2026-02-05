package com.foggy.navigator.tutor.skill;

import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillManager;
import com.foggy.navigator.agent.framework.skill.SkillParser;
import com.foggy.navigator.agent.framework.skill.impl.DefaultSkillManager;
import com.foggy.navigator.common.dto.SkillConfigDTO;
import com.foggy.navigator.common.enums.SkillScope;
import com.foggy.navigator.common.enums.SkillStatus;
import com.foggy.navigator.common.form.SkillBasicInfo;
import com.foggy.navigator.common.form.SkillConfigForm;
import com.foggy.navigator.common.form.SkillExecutionConfig;
import com.foggy.navigator.common.form.SkillTriggerConfig;
import com.foggy.navigator.spi.config.SkillConfigManager;
import com.foggy.navigator.tutor.config.TutorAgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Tutor Skill 初始化器
 * 在启动时加载并注册 Tutor Agent 的 Skill
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TutorSkillInitializer {

    private final TutorAgentProperties properties;
    private final SkillManager skillManager;
    private final SkillParser skillParser;

    @Autowired(required = false)
    private SkillConfigManager skillConfigManager;

    @PostConstruct
    public void init() {
        if (!properties.isInitSkillsOnStartup()) {
            log.info("Skill initialization on startup is disabled");
            return;
        }

        log.info("Initializing tutor-agent skills...");

        // 1. 从数据库加载 Skills（如果 SkillConfigManager 可用）
        if (skillConfigManager != null && skillManager instanceof DefaultSkillManager) {
            DefaultSkillManager defaultManager = (DefaultSkillManager) skillManager;
            defaultManager.loadSkillsFromConfig(properties.getAgentId(), properties.getDefaultTenantId());
            log.info("Loaded skills from database for tutor-agent");
        }

        // 2. 从文件系统加载本地 Skills（作为补充或后备）
        List<Skill> loadedSkills = loadSkillsFromClasspath();

        // 3. 注册默认的系统 Skills 到数据库（如果尚未存在）
        if (skillConfigManager != null) {
            registerSkillsToDatabase(loadedSkills);
        }

        log.info("Tutor-agent skill initialization completed");
    }

    /**
     * 从 classpath 加载 Skills
     */
    private List<Skill> loadSkillsFromClasspath() {
        List<Skill> skills = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/tutor/*.md");

            for (Resource resource : resources) {
                try {
                    String content = readResource(resource);
                    Skill skill = skillParser.parse(content, resource.getFilename());
                    skill.setAgentId(properties.getAgentId());
                    skillManager.registerSkill(skill);
                    skills.add(skill);
                    log.debug("Loaded skill from classpath: {}", skill.getId());
                } catch (Exception e) {
                    log.warn("Failed to load skill from {}: {}", resource.getFilename(), e.getMessage());
                }
            }

            log.info("Loaded {} skills from classpath", resources.length);
        } catch (Exception e) {
            log.warn("Failed to load skills from classpath: {}", e.getMessage());
        }
        return skills;
    }

    /**
     * 注册 Skills 到数据库
     */
    private void registerSkillsToDatabase(List<Skill> skills) {
        for (Skill skill : skills) {
            try {
                Optional<SkillConfigDTO> existing = skillConfigManager.getSkillByName(skill.getId(), null);
                if (existing.isEmpty()) {
                    SkillConfigForm form = createSkillForm(skill);
                    String id = skillConfigManager.saveSkillConfig(form);
                    log.info("Registered skill to database: {} (id={})", skill.getId(), id);
                } else {
                    log.debug("Skill already exists in database: {}", skill.getId());
                }
            } catch (Exception e) {
                log.warn("Failed to register skill {}: {}", skill.getId(), e.getMessage());
            }
        }
    }

    private SkillConfigForm createSkillForm(Skill skill) {
        SkillConfigForm form = new SkillConfigForm();

        SkillBasicInfo basicInfo = new SkillBasicInfo();
        basicInfo.setName(skill.getId());
        basicInfo.setDescription(skill.getDescription());
        basicInfo.setScope(SkillScope.AGENT);
        basicInfo.setAgentId(properties.getAgentId());
        basicInfo.setStatus(SkillStatus.ENABLED);
        basicInfo.setPriority(50); // 默认优先级
        form.setBasicInfo(basicInfo);

        SkillTriggerConfig triggerConfig = new SkillTriggerConfig();
        triggerConfig.setKeywords(skill.getTriggerKeywords());
        triggerConfig.setIntents(skill.getIntents());
        form.setTriggerConfig(triggerConfig);

        SkillExecutionConfig executionConfig = new SkillExecutionConfig();
        executionConfig.setExecutionLogic(skill.getExecutionLogic());
        executionConfig.setOutputFormat(skill.getOutputFormat());
        form.setExecutionConfig(executionConfig);

        return form;
    }

    private String readResource(Resource resource) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
