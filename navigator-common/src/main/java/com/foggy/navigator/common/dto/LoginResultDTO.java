package com.foggy.navigator.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录结果DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResultDTO {

    /**
     * JWT Token
     */
    private String token;

    /**
     * Token类型（Bearer）
     */
    private String tokenType;

    /**
     * 过期时间（秒）
     */
    private Long expiresIn;

    /**
     * 用户信息
     */
    private UserDTO user;
}
