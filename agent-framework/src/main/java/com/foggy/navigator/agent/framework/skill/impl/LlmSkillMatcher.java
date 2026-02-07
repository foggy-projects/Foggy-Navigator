package com.foggy.navigator.agent.framework.skill.impl;

import com.foggy.navigator.agent.framework.llm.LlmAdapter;
import com.foggy.navigator.agent.framework.llm.LlmMessage;
import com.foggy.navigator.agent.framework.llm.LlmRequest;
import com.foggy.navigator.agent.framework.llm.LlmResponse;
import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillMatcher;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 基于 LLM 的 Skill 匹配器
 * 使用一次轻量 LLM 调用来判断用户消息最匹配哪个 Skill
 * 如果 LLM 调用失败，回退到 KeywordSkillMatcher
 */
@Slf4j
public class LlmSkillMatcher implements SkillMatcher {

    private static final String ROUTING_SYSTEM_PROMPT =
            "You are a skill routing engine. Given a user message and a list of available skills, " +
            "determine which skill (if any) best matches the user's intent.\n\n" +
            "Rules:\n" +
            "- Reply with ONLY the skill name (exactly as listed) if there is a match\n" +
            "- Reply with ONLY the word \"NONE\" if no skill matches\n" +
            "- Do not add any explanation or extra text";

    private static final String ROUTING_MODEL = "gpt-4o-mini";

    private final LlmAdapter llmAdapter;
    private final KeywordSkillMatcher fallbackMatcher;

    public LlmSkillMatcher(LlmAdapter llmAdapter) {
        this.llmAdapter = llmAdapter;
        this.fallbackMatcher = new KeywordSkillMatcher();
    }

    @Override
    public Skill match(String userMessage, List<Skill> availableSkills) {
        if (availableSkills == null || availableSkills.isEmpty()) {
            return null;
        }

        try {
            String prompt = buildRoutingPrompt(userMessage, availableSkills);

            LlmRequest request = LlmRequest.builder()
                    .model(ROUTING_MODEL)
                    .temperature(0.0)
                    .systemPrompt(ROUTING_SYSTEM_PROMPT)
                    .messages(List.of(LlmMessage.user(prompt)))
                    .timeoutSeconds(10)
                    .maxRetries(0)
                    .build();

            LlmResponse response = llmAdapter.chat(request);
            String result = response.getContent() != null ? response.getContent().trim() : "";

            if ("NONE".equalsIgnoreCase(result) || result.isBlank()) {
                log.debug("LLM skill routing: no match for message: {}", truncate(userMessage, 80));
                return null;
            }

            // 在可用 skills 中查找匹配名称
            for (Skill skill : availableSkills) {
                if (skill.getName().equalsIgnoreCase(result)) {
                    log.info("LLM skill routing matched: skill={}, message={}",
                            skill.getName(), truncate(userMessage, 80));
                    return skill;
                }
            }

            log.warn("LLM returned unknown skill name: '{}', falling back to keyword matching", result);
            return fallbackMatcher.match(userMessage, availableSkills);

        } catch (Exception e) {
            log.warn("LLM skill routing failed, falling back to keyword matching: {}", e.getMessage());
            return fallbackMatcher.match(userMessage, availableSkills);
        }
    }

    @Override
    public double calculateScore(String userMessage, Skill skill) {
        // LLM 模式下 score 为二元判断，委托给 fallback 做连续打分
        return fallbackMatcher.calculateScore(userMessage, skill);
    }

    private String buildRoutingPrompt(String userMessage, List<Skill> skills) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Available Skills\n\n");
        for (Skill skill : skills) {
            sb.append("- **").append(skill.getName()).append("**: ")
              .append(skill.getDescription()).append("\n");
        }
        sb.append("\n## User Message\n\n");
        sb.append(userMessage);
        return sb.toString();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
