package com.foggy.navigator.claude.worker.model.form;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Open API Agent 查询表单
 */
@Data
public class OpenApiQueryForm {

    /** 查询内容（合同字段，与 question 等价，任选其一） */
    private String message;

    /** 查询内容（兼容旧字段，与 message 等价） */
    private String question;

    /** 多轮会话标识（可选，首次不传则平台自动生成） */
    private String contextId;

    /** 最大交互轮数（可选，默认 3） */
    private Integer maxTurns;

    /** 本次 ask 使用的模型配置 ID（可选；优先于 metadata.modelConfigId） */
    private String modelConfigId;

    /** 原生系统提示词，仅对支持该能力的 Agent 生效 */
    private String systemPrompt;

    /** 首轮附加消息，仅在首次创建会话时拼接到用户消息前面 */
    private String firstMsg;

    /** 扩展元数据 */
    private Map<String, Object> metadata;

    /** Client App 透明会话上下文，仅用于会话摘要保存，不进入 Agent 执行链路 */
    private Map<String, Object> clientContext;

    /** 上游已上传附件元数据和 URL；不转换为旧 images 字段 */
    private List<Map<String, Object>> attachments;

    /** 本次任务工作目录。Navigator 只透传给 BizWorker 执行策略，不做插件热加载或租户 sandbox。 */
    @JsonAlias({"workDir", "working_dir", "workingDirectory", "working_directory"})
    private String workdir;

    /** 本次任务允许访问的目录集合。 */
    @JsonProperty("allowed_dirs")
    @JsonAlias({"allowedDirs", "allowed_directories", "allowedDirectories"})
    private List<String> allowedDirs;

    /** 本次任务允许使用的工具集合。 */
    @JsonProperty("allowed_tools")
    @JsonAlias({"allowedTools", "authorized_tools", "authorizedTools", "tool_allowlist", "toolAllowlist"})
    private List<String> allowedTools;

    /**
     * 获取实际消息内容（优先 message，回退 question）
     */
    public String resolveMessage() {
        if (message != null && !message.isBlank()) return message;
        return question;
    }
}
