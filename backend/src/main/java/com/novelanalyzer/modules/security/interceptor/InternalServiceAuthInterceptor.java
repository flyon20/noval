package com.novelanalyzer.modules.security.interceptor;

import com.novelanalyzer.modules.security.service.InternalServiceAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class InternalServiceAuthInterceptor implements HandlerInterceptor {

    private final InternalServiceAuthService internalServiceAuthService;

    public InternalServiceAuthInterceptor(InternalServiceAuthService internalServiceAuthService) {
        this.internalServiceAuthService = internalServiceAuthService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) {
            return true;
        }
        internalServiceAuthService.assertLangGraphWorkerCaller(request);
        return true;
    }
}
