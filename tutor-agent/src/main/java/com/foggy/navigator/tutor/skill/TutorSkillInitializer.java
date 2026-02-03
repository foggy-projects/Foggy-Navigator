package com.foggy.navigator.tutor.skill;

import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillManager;
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
import java.time.LocalDateTime;
import java.util.Arrays;
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
        loadSkillsFromClasspath();

        // 3. 注册默认的系统 Skills 到数据库（如果尚未存在）
        if (skillConfigManager != null) {
            registerDefaultSkillsToDatabase();
        }

        log.info("Tutor-agent skill initialization completed");
    }

    /**
     * 从 classpath 加载 Skills
     */
    private void loadSkillsFromClasspath() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/tutor/*.md");

            for (Resource resource : resources) {
                try {
                    String content = readResource(resource);
                    Skill skill = parseSkillFromMarkdown(content, resource.getFilename());
                    skill.setAgentId(properties.getAgentId());
                    skillManager.registerSkill(skill);
                    log.debug("Loaded skill from classpath: {}", skill.getId());
                } catch (Exception e) {
                    log.warn("Failed to load skill from {}: {}", resource.getFilename(), e.getMessage());
                }
            }

            log.info("Loaded {} skills from classpath", resources.length);
        } catch (Exception e) {
            log.warn("Failed to load skills from classpath: {}", e.getMessage());
        }
    }

    /**
     * 注册默认 Skills 到数据库
     */
    private void registerDefaultSkillsToDatabase() {
        List<SkillDefinition> defaultSkills = getDefaultSkillDefinitions();

        for (SkillDefinition def : defaultSkills) {
            try {
                Optional<SkillConfigDTO> existing = skillConfigManager.getSkillByName(def.name, null);
                if (existing.isEmpty()) {
                    SkillConfigForm form = createSkillForm(def);
                    String id = skillConfigManager.saveSkillConfig(form);
                    log.info("Registered default skill to database: {} (id={})", def.name, id);
                } else {
                    log.debug("Skill already exists in database: {}", def.name);
                }
            } catch (Exception e) {
                log.warn("Failed to register skill {}: {}", def.name, e.getMessage());
            }
        }
    }

    private SkillConfigForm createSkillForm(SkillDefinition def) {
        SkillConfigForm form = new SkillConfigForm();

        SkillBasicInfo basicInfo = new SkillBasicInfo();
        basicInfo.setName(def.name);
        basicInfo.setDescription(def.description);
        basicInfo.setScope(SkillScope.AGENT);
        basicInfo.setAgentId(properties.getAgentId());
        basicInfo.setStatus(SkillStatus.ENABLED);
        basicInfo.setPriority(def.priority);
        form.setBasicInfo(basicInfo);

        SkillTriggerConfig triggerConfig = new SkillTriggerConfig();
        triggerConfig.setKeywords(def.keywords);
        triggerConfig.setIntents(def.intents);
        form.setTriggerConfig(triggerConfig);

        SkillExecutionConfig executionConfig = new SkillExecutionConfig();
        executionConfig.setExecutionLogic(def.executionLogic);
        executionConfig.setOutputFormat(def.outputFormat);
        form.setExecutionConfig(executionConfig);

        return form;
    }

    private List<SkillDefinition> getDefaultSkillDefinitions() {
        return Arrays.asList(
            new SkillDefinition(
                "guide-initial-setup",
                "引导初始配置",
                Arrays.asList("初始配置", "开始配置", "系统配置", "如何开始"),
                Arrays.asList("initial_setup", "start_config"),
                "1. 检查当前系统配置状态\n2. 列出已配置的 Git 凭证\n3. 引导用户创建编码会话\n4. 提供配置建议",
                "**初始配置向导**\n\n根据当前状态提供逐步指导",
                10
            ),
            new SkillDefinition(
                "dispatch-coding-task",
                "分派编码任务",
                Arrays.asList("帮我写代码", "创建功能", "开发任务", "编程"),
                Arrays.asList("coding_task", "create_feature"),
                "1. 确认用户需求\n2. 选择目标项目和分支\n3. 创建编码会话\n4. 发送任务指令",
                "**任务已分派**\n\n会话 ID: {conversationId}\n状态: 处理中",
                20
            ),
            new SkillDefinition(
                "check-task-progress",
                "查询任务进度",
                Arrays.asList("任务进度", "什么状态", "查看进度", "完成了吗"),
                Arrays.asList("check_progress", "task_status"),
                "1. 获取会话状态\n2. 查看最新消息\n3. 报告进度",
                "**任务进度**\n\n状态: {status}\n进度: {progress}",
                30
            ),
            new SkillDefinition(
                "help-troubleshoot",
                "帮助排查问题",
                Arrays.asList("遇到问题", "报错了", "出错", "帮助"),
                Arrays.asList("troubleshoot", "help"),
                "1. 了解问题详情\n2. 检查相关配置\n3. 提供解决建议",
                "**问题排查**\n\n根据错误信息提供解决方案",
                40
            )
        );
    }

    private String readResource(Resource resource) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private Skill parseSkillFromMarkdown(String content, String filename) {
        // 简单解析 Markdown 格式的 Skill 定义
        Skill.SkillBuilder builder = Skill.builder();

        String[] sections = content.split("\n# ");
        for (String section : sections) {
            if (section.startsWith("Skill ID") || section.startsWith("skill id")) {
                String id = extractValue(section);
                builder.id(id);
                builder.name(id);
            } else if (section.startsWith("Skill标题") || section.startsWith("skill标题")) {
                builder.name(extractValue(section));
            } else if (section.startsWith("触发条件")) {
                builder.triggerKeywords(extractList(section));
            } else if (section.startsWith("意图")) {
                builder.intents(extractList(section));
            } else if (section.startsWith("执行逻辑")) {
                builder.executionLogic(extractValue(section));
            } else if (section.startsWith("输出格式")) {
                builder.outputFormat(extractValue(section));
            } else if (section.startsWith("分派条件")) {
                builder.delegationCondition(extractValue(section));
            } else if (section.contains("描述") || section.contains("description")) {
                builder.description(extractValue(section));
            }
        }

        builder.loadedAt(LocalDateTime.now());

        // 如果没有解析到 ID，使用文件名
        Skill skill = builder.build();
        if (skill.getId() == null || skill.getId().isEmpty()) {
            String id = filename.replace(".md", "");
            skill.setId(id);
            if (skill.getName() == null) {
                skill.setName(id);
            }
        }

        return skill;
    }

    private String extractValue(String section) {
        String[] lines = section.split("\n");
        if (lines.length > 1) {
            return String.join("\n", Arrays.copyOfRange(lines, 1, lines.length)).trim();
        }
        return "";
    }

    private List<String> extractList(String section) {
        String[] lines = section.split("\n");
        return Arrays.stream(lines)
            .skip(1)
            .map(String::trim)
            .filter(line -> line.startsWith("-") || line.startsWith("*"))
            .map(line -> line.substring(1).trim())
            .collect(Collectors.toList());
    }

    /**
     * 内部类：Skill 定义
     */
    private static class SkillDefinition {
        final String name;
        final String description;
        final List<String> keywords;
        final List<String> intents;
        final String executionLogic;
        final String outputFormat;
        final int priority;

        SkillDefinition(String name, String description, List<String> keywords,
                       List<String> intents, String executionLogic, String outputFormat, int priority) {
            this.name = name;
            this.description = description;
            this.keywords = keywords;
            this.intents = intents;
            this.executionLogic = executionLogic;
            this.outputFormat = outputFormat;
            this.priority = priority;
        }
    }
}
