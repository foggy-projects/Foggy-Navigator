package com.foggy.navigator.claude.worker.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息计数
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageCount {
    private int user;
    private int assistant;
    private int total;
}
