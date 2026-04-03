package com.foggy.navigator.common.form;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 共享密钥更新表单
 */
@Data
public class SharingKeyUpdateForm {

    /** 标签/描述 */
    private String label;

    /** 系统提示词 */
    private String systemPrompt;

    /** Claude Worker 最大轮数 */
    private Integer maxTurns;

    /** 每日调用次数限额 */
    private Integer maxDailyCalls;

    /** 过期时间 */
    private LocalDateTime expiresAt;

    /** 是否启用 */
    private Boolean enabled;

    /** 允许的操作列表（null 表示不修改，空列表表示允许全部） */
    private java.util.List<String> allowedOperations;
}
