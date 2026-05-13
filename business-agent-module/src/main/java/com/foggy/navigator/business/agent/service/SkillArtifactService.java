package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.SkillArtifactFileDTO;
import com.foggy.navigator.business.agent.model.dto.SkillArtifactSliceDTO;
import com.foggy.navigator.business.agent.model.dto.SkillArtifactTreeDTO;
import com.foggy.navigator.business.agent.model.entity.SkillEntity;
import com.foggy.navigator.business.agent.model.form.SkillResourceForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SkillArtifactService {

    private static final int DEFAULT_MAX_CHARS = 8000;
    private static final int MAX_CHARS_LIMIT = 20000;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".md", ".txt", ".json", ".yaml", ".yml", ".sh", ".ps1", ".properties");

    private final SkillRegistryService skillRegistryService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public SkillArtifactTreeDTO tree(String tenantId, String clientAppId, String skillId) {
        skillRegistryService.checkClientAppSkillAccess(tenantId, clientAppId, skillId);
        SkillEntity skill = skillRegistryService.getSkill(tenantId, skillId);

        SkillArtifactTreeDTO tree = new SkillArtifactTreeDTO();
        tree.setSkillId(skillId);
        tree.setArtifactVersion("current");
        tree.getFiles().add(file("SKILL.md", normalize(skill.getMarkdownBody()), skillId));
        for (SkillResourceForm resource : parseResources(skill)) {
            validateArtifactPath(resource.getPath());
            tree.getFiles().add(file(resource.getPath(), normalize(resource.getContent()), skillId));
        }
        return tree;
    }

    @Transactional(readOnly = true)
    public SkillArtifactSliceDTO slice(
            String tenantId,
            String clientAppId,
            String skillId,
            String path,
            Integer startLine,
            Integer startColumn,
            Integer maxChars) {
        skillRegistryService.checkClientAppSkillAccess(tenantId, clientAppId, skillId);
        SkillEntity skill = skillRegistryService.getSkill(tenantId, skillId);
        String safePath = validateArtifactPath(path);
        String content = normalize(resolveContent(skill, safePath));
        int effectiveStartLine = startLine == null ? 1 : startLine;
        int effectiveStartColumn = startColumn == null ? 1 : startColumn;
        int effectiveMaxChars = maxChars == null ? DEFAULT_MAX_CHARS : maxChars;
        if (effectiveStartLine < 1 || effectiveStartColumn < 1) {
            throw new IllegalArgumentException("SKILL_ARTIFACT_SLICE_RANGE_INVALID");
        }
        if (effectiveMaxChars < 1 || effectiveMaxChars > MAX_CHARS_LIMIT) {
            throw new IllegalArgumentException("SKILL_ARTIFACT_SLICE_TOO_LARGE");
        }

        int startChar = charIndexAt(content, effectiveStartLine, effectiveStartColumn);
        int endChar = startChar;
        int copied = 0;
        while (endChar < content.length() && copied < effectiveMaxChars) {
            int codePoint = content.codePointAt(endChar);
            endChar += Character.charCount(codePoint);
            copied++;
        }

        Position next = positionAt(content, endChar);
        Position end = copied == 0 ? next : positionAt(content, content.offsetByCodePoints(endChar, -1));

        SkillArtifactSliceDTO dto = new SkillArtifactSliceDTO();
        dto.setSkillId(skillId);
        dto.setPath(safePath);
        dto.setEncoding("UTF-8");
        dto.setLineEnding("LF_NORMALIZED");
        dto.setStartLine(effectiveStartLine);
        dto.setStartColumn(effectiveStartColumn);
        dto.setEndLine(end.line());
        dto.setEndColumn(end.column());
        dto.setNextLine(next.line());
        dto.setNextColumn(next.column());
        dto.setMaxChars(effectiveMaxChars);
        dto.setTruncated(endChar < content.length());
        dto.setTotalLines(lineCount(content));
        dto.setContent(content.substring(startChar, endChar));
        return dto;
    }

    private SkillArtifactFileDTO file(String path, String content, String skillId) {
        SkillArtifactFileDTO file = new SkillArtifactFileDTO();
        file.setPath(path);
        file.setType("file");
        file.setSize(content.getBytes(StandardCharsets.UTF_8).length);
        file.setLineCount(lineCount(content));
        file.setSliceUrl("/api/v1/open/skills/" + skillId
                + "/files/slice?path=" + encode(path)
                + "&startLine=1&startColumn=1&maxChars=" + DEFAULT_MAX_CHARS);
        return file;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String resolveContent(SkillEntity skill, String path) {
        if ("SKILL.md".equals(path)) {
            return skill.getMarkdownBody();
        }
        return parseResources(skill).stream()
                .filter(resource -> path.equals(resource.getPath()))
                .findFirst()
                .map(SkillResourceForm::getContent)
                .orElseThrow(() -> new IllegalArgumentException("SKILL_ARTIFACT_FILE_NOT_FOUND"));
    }

    private List<SkillResourceForm> parseResources(SkillEntity skill) {
        if (skill == null || !StringUtils.hasText(skill.getResourcesJson())) {
            return List.of();
        }
        try {
            List<SkillResourceForm> resources = objectMapper.readValue(
                    skill.getResourcesJson(), new TypeReference<List<SkillResourceForm>>() {});
            return resources == null ? List.of() : resources;
        } catch (Exception e) {
            throw new IllegalStateException("SKILL_ARTIFACT_UNSUPPORTED_ENCODING", e);
        }
    }

    private String validateArtifactPath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("SKILL_ARTIFACT_PATH_INVALID");
        }
        String normalized = path.trim();
        if (normalized.contains("\\") || normalized.startsWith("/") || normalized.startsWith("./")
                || normalized.startsWith("../") || normalized.contains("/../") || normalized.contains("/./")
                || normalized.contains("//") || normalized.contains(":") || normalized.endsWith("/")) {
            throw new IllegalArgumentException("SKILL_ARTIFACT_PATH_INVALID");
        }
        if (!"SKILL.md".equals(normalized)
                && !(normalized.startsWith("references/") || normalized.startsWith("assets/"))) {
            throw new IllegalArgumentException("SKILL_ARTIFACT_PATH_INVALID");
        }
        if (TEXT_EXTENSIONS.stream().noneMatch(normalized::endsWith)) {
            throw new IllegalArgumentException("SKILL_ARTIFACT_BINARY_FILE");
        }
        return normalized;
    }

    private int charIndexAt(String content, int line, int column) {
        int currentLine = 1;
        int index = 0;
        while (currentLine < line && index < content.length()) {
            int codePoint = content.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\n') {
                currentLine++;
            }
        }
        if (currentLine != line) {
            throw new IllegalArgumentException("SKILL_ARTIFACT_SLICE_RANGE_INVALID");
        }

        int currentColumn = 1;
        while (currentColumn < column && index < content.length()) {
            int codePoint = content.codePointAt(index);
            if (codePoint == '\n') {
                break;
            }
            index += Character.charCount(codePoint);
            currentColumn++;
        }
        if (currentColumn != column) {
            throw new IllegalArgumentException("SKILL_ARTIFACT_SLICE_RANGE_INVALID");
        }
        return index;
    }

    private Position positionAt(String content, int charIndex) {
        int line = 1;
        int column = 1;
        int index = 0;
        while (index < charIndex) {
            int codePoint = content.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }

    private int lineCount(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private String normalize(String content) {
        return content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
    }

    private record Position(int line, int column) {}
}
