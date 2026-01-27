package com.foggy.navigator.auth.aspect;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 认证切面
 */
@Slf4j
@Aspect
@Component
public class AuthAspect {

    @Around("@annotation(com.foggy.navigator.common.annotation.RequireAuth) || " +
            "@within(com.foggy.navigator.common.annotation.RequireAuth)")
    public Object checkAuth(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取当前用户
        CurrentUser currentUser = UserContext.getCurrentUser();
        if (currentUser == null) {
            throw new SecurityException("未登录，请先登录");
        }

        // 获取注解
        RequireAuth requireAuth = getRequireAuth(joinPoint);
        if (requireAuth == null) {
            return joinPoint.proceed();
        }

        // 检查角色
        String[] requiredRoles = requireAuth.roles();
        if (requiredRoles.length > 0) {
            boolean hasRole = Arrays.stream(requiredRoles)
                    .anyMatch(currentUser::hasRole);

            // 超级管理员拥有所有权限
            if (!hasRole && !currentUser.isSuperAdmin()) {
                throw new SecurityException("无权限访问此接口");
            }
        }

        return joinPoint.proceed();
    }

    private RequireAuth getRequireAuth(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 先检查方法上的注解
        RequireAuth methodAnnotation = method.getAnnotation(RequireAuth.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }

        // 再检查类上的注解
        return method.getDeclaringClass().getAnnotation(RequireAuth.class);
    }
}
