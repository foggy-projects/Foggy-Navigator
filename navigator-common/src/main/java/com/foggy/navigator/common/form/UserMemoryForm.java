package com.foggy.navigator.common.form;

import com.foggy.navigator.common.enums.UserMemoryCategory;
import lombok.Data;

/**
 * 用户记忆表单
 */
@Data
public class UserMemoryForm {

    /**
     * 记忆类别（可选，默认 FACT）
     */
    private UserMemoryCategory category;

    /**
     * 记忆内容（必填）
     */
    private String content;
}
