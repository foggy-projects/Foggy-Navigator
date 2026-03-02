package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 将平台 Skill（如 ask-agent）推送到在线 Worker 的 ~/.claude/skills/。
 * <p>
 * ask-agent 技能内容包含动态 Agent 列表，由 Navigator 后端生成后推送给 Worker，
 * Worker 自身无需反向调用 Navigator API。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformSkillSyncer {

    private final ClaudeWorkerRepository workerRepository;
    private final ClaudeWorkerService workerService;
    private final CodingAgentRepository codingAgentRepository;

    @Value("${navigator.api.external-url:http://localhost:${server.port:8112}}")
    private String navigatorApiBase;

    private String skillTemplate;

    /**
     * 启动后异步推送（延迟等 health checker 先跑一轮）
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        try {
            Thread.sleep(90_000); // 等待 Worker 上线
            syncAllOnlineWorkers();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 同步所有在线 Worker
     */
    public void syncAllOnlineWorkers() {
        var workers = workerRepository.findAll().stream()
                .filter(w -> "ONLINE".equals(w.getStatus()))
                .toList();
        log.info("Syncing ask-agent skill to {} online workers", workers.size());
        for (var worker : workers) {
            syncWorkerSkills(worker);
        }
    }

    /**
     * 同步单个 Worker（by workerId）
     */
    public void syncWorkerSkills(String workerId) {
        var worker = workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
        syncWorkerSkills(worker);
    }

    private void syncWorkerSkills(ClaudeWorkerEntity worker) {
        try {
            String skillContent = generateAskAgentSkill(worker);
            ClaudeWorkerClient client = workerService.createClient(worker);
            client.deploySkills(Map.of("ask-agent", skillContent))
                    .block(Duration.ofSeconds(10));
            log.info("Synced ask-agent skill to worker: {} ({})", worker.getName(), worker.getWorkerId());
        } catch (Exception e) {
            log.warn("Failed to sync skills to worker {} ({}): {}",
                    worker.getName(), worker.getWorkerId(), e.getMessage());
        }
    }

    private String generateAskAgentSkill(ClaudeWorkerEntity worker) {
        String template = loadTemplate();

        // 获取该 Worker 所属用户的所有 Claude Worker Agent
        List<CodingAgentEntity> agents = codingAgentRepository
                .findByUserIdOrderByCreatedAtDesc(worker.getUserId())
                .stream()
                .filter(e -> "LOCAL_CLAUDE_WORKER".equals(e.getAgentType()))
                .toList();

        StringBuilder table = new StringBuilder();
        for (CodingAgentEntity agent : agents) {
            String desc = agent.getDescription() != null ? agent.getDescription() : "";
            // 截断过长描述，保留单行
            if (desc.contains("\n")) {
                desc = desc.substring(0, desc.indexOf('\n'));
            }
            if (desc.length() > 100) {
                desc = desc.substring(0, 97) + "...";
            }
            table.append(String.format("| %s | %s | %s |\n",
                    agent.getName(), agent.getAgentId(), desc));
        }

        return template
                .replace("{{AGENT_TABLE}}", table.toString())
                .replace("{{NAVIGATOR_API_BASE}}", navigatorApiBase);
    }

    private String loadTemplate() {
        if (skillTemplate != null) {
            return skillTemplate;
        }
        try {
            ClassPathResource resource = new ClassPathResource("platform-skills/ask-agent/SKILL.md.template");
            try (InputStream is = resource.getInputStream()) {
                skillTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load ask-agent SKILL.md template", e);
            throw new IllegalStateException("Missing ask-agent SKILL.md template", e);
        }
        return skillTemplate;
    }
}
