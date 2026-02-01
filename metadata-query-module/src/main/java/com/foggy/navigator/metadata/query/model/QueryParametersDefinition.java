package com.foggy.navigator.metadata.query.model;

import lombok.Data;

import java.util.List;

/**
 * 查询参数定义
 */
@Data
public class QueryParametersDefinition {
    /**
     * 查询ID
     */
    private String queryId;

    /**
     * 参数列表
     */
    private List<ParameterInfo> parameters;

    /**
     * 参数信息
     */
    @Data
    public static class ParameterInfo {
        /**
         * 参数名
         */
        private String name;

        /**
         * 参数类型
         */
        private String type;

        /**
         * 是否必需
         */
        private Boolean required;

        /**
         * 默认值
         */
        private Object defaultValue;

        /**
         * 描述
         */
        private String description;
    }
}
