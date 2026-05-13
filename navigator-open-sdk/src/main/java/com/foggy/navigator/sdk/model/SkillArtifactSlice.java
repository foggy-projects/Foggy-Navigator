package com.foggy.navigator.sdk.model;

public class SkillArtifactSlice {
    private String skillId;
    private String path;
    private String encoding;
    private String lineEnding;
    private int startLine;
    private int startColumn;
    private int endLine;
    private int endColumn;
    private int nextLine;
    private int nextColumn;
    private int maxChars;
    private boolean truncated;
    private int totalLines;
    private String content;

    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getEncoding() { return encoding; }
    public void setEncoding(String encoding) { this.encoding = encoding; }

    public String getLineEnding() { return lineEnding; }
    public void setLineEnding(String lineEnding) { this.lineEnding = lineEnding; }

    public int getStartLine() { return startLine; }
    public void setStartLine(int startLine) { this.startLine = startLine; }

    public int getStartColumn() { return startColumn; }
    public void setStartColumn(int startColumn) { this.startColumn = startColumn; }

    public int getEndLine() { return endLine; }
    public void setEndLine(int endLine) { this.endLine = endLine; }

    public int getEndColumn() { return endColumn; }
    public void setEndColumn(int endColumn) { this.endColumn = endColumn; }

    public int getNextLine() { return nextLine; }
    public void setNextLine(int nextLine) { this.nextLine = nextLine; }

    public int getNextColumn() { return nextColumn; }
    public void setNextColumn(int nextColumn) { this.nextColumn = nextColumn; }

    public int getMaxChars() { return maxChars; }
    public void setMaxChars(int maxChars) { this.maxChars = maxChars; }

    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }

    public int getTotalLines() { return totalLines; }
    public void setTotalLines(int totalLines) { this.totalLines = totalLines; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
