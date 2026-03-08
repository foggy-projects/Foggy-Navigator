package com.foggy.navigator.common.annotation;

import java.lang.annotation.*;

/**
 * 需要认证注解
 * 标注在 Controller 方法上，表示该接口需要登录
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAuth {

    /**
     * 需要的角色（任意一个即可）
     */
    String[] roles() default {};

    /**
     * 是否需要租户权限检查
     */
    boolean checkTenant() default false;
}
