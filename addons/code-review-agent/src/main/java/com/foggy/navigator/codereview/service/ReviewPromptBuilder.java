package com.foggy.navigator.codereview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.foggy.navigator.codereview.model.entity.CodeReviewConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 构建代码审核 Prompt
 * <p>
 * 根据 MR diff、配置语言等信息，构建发送给 Claude Worker 的审核 prompt。
 */
@Slf4j
@Service
public class ReviewPromptBuilder {

    /**
     * 构建审核 prompt
     *
     * @param config    审核配置
     * @param mrChanges MR 变更数据（从 GitLab API 获取）
     * @return 完整的审核 prompt
     */
    public String build(CodeReviewConfigEntity config, JsonNode mrChanges) {
        String mrTitle = mrChanges.path("title").asText("Untitled");
        String sourceBranch = mrChanges.path("source_branch").asText("");
        String targetBranch = mrChanges.path("target_branch").asText("");
        String projectName = config.getProjectName() != null ? config.getProjectName() : "Unknown";

        String diffContent = extractDiff(mrChanges, config.getMaxDiffLines());
        boolean isZh = "zh".equalsIgnoreCase(config.getReviewLanguage());

        StringBuilder sb = new StringBuilder();

        if (isZh) {
            sb.append("你是一位资深代码审查员。请审查以下 Merge Request 的代码变更。\n\n");
            sb.append("## MR 信息\n");
            sb.append("- 标题: ").append(mrTitle).append("\n");
            sb.append("- 分支: ").append(sourceBranch).append(" → ").append(targetBranch).append("\n");
            sb.append("- 项目: ").append(projectName).append("\n\n");
            sb.append("## 审查要点\n");
            sb.append("- 重点关注：Bug、安全漏洞、性能问题、代码风格、设计模式\n");
            sb.append("- 给出具体、有建设性的建议\n");
            sb.append("- 引用 diff 中的文件路径和行号\n");
            sb.append("- 如果代码质量良好，也请给出正面评价\n\n");
        } else {
            sb.append("You are a senior code reviewer. Analyze the following Merge Request diff and provide a thorough code review.\n\n");
            sb.append("## MR Information\n");
            sb.append("- Title: ").append(mrTitle).append("\n");
            sb.append("- Branch: ").append(sourceBranch).append(" → ").append(targetBranch).append("\n");
            sb.append("- Project: ").append(projectName).append("\n\n");
            sb.append("## Review Guidelines\n");
            sb.append("- Focus on: bugs, security issues, performance problems, code style, design patterns\n");
            sb.append("- Be constructive and specific\n");
            sb.append("- Reference file paths and line numbers from the diff\n");
            sb.append("- Give positive feedback if code quality is good\n\n");
        }

        sb.append("## Output Format (MUST be valid JSON)\n");
        sb.append("```json\n");
        sb.append("{\n");
        if (isZh) {
            sb.append("  \"summary\": \"整体评估（2-3段）\",\n");
        } else {
            sb.append("  \"summary\": \"Overall assessment (2-3 paragraphs)\",\n");
        }
        sb.append("  \"comments\": [\n");
        sb.append("    {\n");
        sb.append("      \"file\": \"path/to/file.java\",\n");
        sb.append("      \"line\": 42,\n");
        sb.append("      \"severity\": \"critical|warning|suggestion|nitpick\",\n");
        if (isZh) {
            sb.append("      \"comment\": \"审查意见\"\n");
        } else {
            sb.append("      \"comment\": \"Review comment\"\n");
        }
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n\n");

        sb.append("## Diff\n");
        sb.append("```diff\n");
        sb.append(diffContent);
        sb.append("\n```\n");

        return sb.toString();
    }

    /**
     * 从 MR changes 中提取 unified diff，按 maxDiffLines 截断
     */
    private String extractDiff(JsonNode mrChanges, int maxDiffLines) {
        JsonNode changes = mrChanges.path("changes");
        if (!changes.isArray() || changes.isEmpty()) {
            return "(no changes)";
        }

        StringBuilder diffBuilder = new StringBuilder();
        int lineCount = 0;

        for (JsonNode change : changes) {
            String newPath = change.path("new_path").asText("");
            String diff = change.path("diff").asText("");

            if (diff.isEmpty()) continue;

            // 添加文件头
            String header = "--- a/" + change.path("old_path").asText("") + "\n"
                    + "+++ b/" + newPath + "\n";
            diffBuilder.append(header);
            lineCount += 2;

            // 添加 diff 内容
            String[] lines = diff.split("\n");
            for (String line : lines) {
                if (lineCount >= maxDiffLines) {
                    diffBuilder.append("\n... (diff truncated at ").append(maxDiffLines).append(" lines) ...\n");
                    return diffBuilder.toString();
                }
                diffBuilder.append(line).append("\n");
                lineCount++;
            }
            diffBuilder.append("\n");
        }

        return diffBuilder.toString();
    }
}
