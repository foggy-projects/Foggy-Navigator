package com.foggy.navigator.coding.agent.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 验证错误
 * 对应验证服务接口的错误格式
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationError {

    /**
     * 文件名
     * 例如: ProductModel.tm
     */
    private String file;

    /**
     * 类型
     * 例如: TM, QM
     */
    private String type;

    /**
     * 错误信息
     */
    private String message;

    /**
     * 错误代码
     * 例如: FIELD_NOT_FOUND, MODEL_NOT_FOUND, SYNTAX_ERROR
     */
    private String code;

    /**
     * 修复建议
     */
    private String suggestion;
}
