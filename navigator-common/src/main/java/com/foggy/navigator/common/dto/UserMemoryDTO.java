package com.foggy.navigator.common.dto;

import com.foggy.navigator.common.enums.UserMemoryCategory;
import com.foggy.navigator.common.enums.UserMemorySource;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户记忆 DTO
 */
@Data
public class UserMemoryDTO {

    private String id;
    private String userId;
    private UserMemoryCategory category;
    private String content;
    private UserMemorySource source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
