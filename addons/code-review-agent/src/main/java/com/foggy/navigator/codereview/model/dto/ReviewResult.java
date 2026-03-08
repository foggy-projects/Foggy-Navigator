package com.foggy.navigator.codereview.model.dto;

import lombok.Data;

import java.util.List;

/**
 * AI 审核结果（解析后的结构化数据）
 */
@Data
public class ReviewResult {

    /** 整体评估总结 */
    private String summary;

    /** 行级评论列表 */
    private List<InlineComment> comments;

    @Data
    public static class InlineComment {
        /** 文件路径 */
        private String file;
        /** 行号（新文件中的行号） */
        private int line;
        /** 严重程度: critical, warning, suggestion, nitpick */
        private String severity;
        /** 评论内容 */
        private String comment;
    }
}
