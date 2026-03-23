package com.novelanalyzer.modules.security.interceptor;

import com.novelanalyzer.common.context.AuthUser;
import com.novelanalyzer.common.context.AuthUserHolder;
import com.novelanalyzer.common.exception.BusinessException;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.modules.security.annotation.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Component
public class RequireRoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) {
            return true;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequireRole requireRole = findRequireRole(handlerMethod);
        if (requireRole == null) {
            return true;
        }

        AuthUser authUser = AuthUserHolder.get();
        if (authUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "unauthorized");
        }

        Set<String> requiredRoles = new HashSet<>(Arrays.asList(requireRole.value()));
        if (!authUser.hasAnyRole(requiredRoles)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "forbidden");
        }
        return true;
    }

    private RequireRole findRequireRole(HandlerMethod handlerMethod) {
        RequireRole methodRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (methodRole != null) {
            return methodRole;
        }
        return handlerMethod.getBeanType().getAnnotation(RequireRole.class);
    }
}
