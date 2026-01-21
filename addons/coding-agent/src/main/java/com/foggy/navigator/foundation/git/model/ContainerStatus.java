package com.foggy.navigator.foundation.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 容器状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerStatus {

    /**
     * 容器ID
     */
    private String id;

    /**
     * 容器名称
     */
    private String name;

    /**
     * 是否运行中
     */
    private boolean running;

    /**
     * 状态描述
     */
    private String status;
}
