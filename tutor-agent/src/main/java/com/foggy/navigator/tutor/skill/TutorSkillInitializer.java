package com.foggy.navigator.tutor.skill;

import com.foggy.navigator.agent.framework.skill.SkillManager;
import com.foggy.navigator.tutor.config.TutorAgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Tutor Skill 初始化器
 * 应用就绪后异步加载并注册 Tutor Agent 的 Skill，不阻塞启动
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TutorSkillInitializer {

    private final TutorAgentProperties properties;
    private final SkillManager skillManager;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!properties.isInitSkillsOnStartup()) {
            log.info("Skill initialization on startup is disabled");
            return;
        }

        log.info("Initializing tutor-agent skills from: {}", properties.getSkillsDirectory());

        // 从配置的目录加载 Skills
        skillManager.loadSkills(properties.getAgentId(), properties.getSkillsDirectory());

        // 验证加载结果
        int skillCount = skillManager.getSkillsByAgent(properties.getAgentId()).size();
        log.info("Tutor-agent skill initialization completed, loaded {} skills", skillCount);
    }
}
