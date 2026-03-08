package com.foggy.navigator.codereview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codereview.model.dto.ReviewResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 AI 审核响应为结构化的 ReviewResult
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewResultParser {

    private final ObjectMapper objectMapper;

    /** 匹配 markdown 代码块中的 JSON */
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
            "```(?:json)?\\s*\\n?(\\{.*?})\\s*\\n?```",
            Pattern.DOTALL
    );

    /**
     * 解析 AI 响应文本为 ReviewResult
     * <p>
     * 支持：
     * 1. 纯 JSON 字符串
     * 2. markdown ```json ``` 代码块包裹的 JSON
     * 3. 解析失败时回退为纯文本 summary
     */
    public ReviewResult parse(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            ReviewResult result = new ReviewResult();
            result.setSummary("AI returned empty response.");
            result.setComments(Collections.emptyList());
            return result;
        }

        String json = extractJson(responseText);

        try {
            return objectMapper.readValue(json, ReviewResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse AI review response as JSON, falling back to plain text summary: {}",
                    e.getMessage());
            ReviewResult result = new ReviewResult();
            result.setSummary(responseText);
            result.setComments(Collections.emptyList());
            return result;
        }
    }

    /**
     * 从响应文本中提取 JSON 字符串
     */
    private String extractJson(String text) {
        // 尝试从 markdown 代码块中提取
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // 尝试找到第一个 { 和最后一个 }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }

        // 原样返回，让 Jackson 尝试解析
        return text;
    }

    /**
     * 根据 severity 返回对应的前缀图标
     */
    public static String severityIcon(String severity) {
        if (severity == null) return "💬";
        return switch (severity.toLowerCase()) {
            case "critical" -> "🔴 **[Critical]**";
            case "warning" -> "🟡 **[Warning]**";
            case "suggestion" -> "💡 **[Suggestion]**";
            case "nitpick" -> "📝 **[Nitpick]**";
            default -> "💬";
        };
    }
}
