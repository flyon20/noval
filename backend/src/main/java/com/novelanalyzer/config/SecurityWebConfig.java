package com.novelanalyzer.config;

import com.novelanalyzer.modules.security.interceptor.RequireRoleInterceptor;
import com.novelanalyzer.modules.security.interceptor.InternalServiceAuthInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityWebConfig implements WebMvcConfigurer {

    private final RequireRoleInterceptor requireRoleInterceptor;
    private final InternalServiceAuthInterceptor internalServiceAuthInterceptor;

    public SecurityWebConfig(RequireRoleInterceptor requireRoleInterceptor,
                             InternalServiceAuthInterceptor internalServiceAuthInterceptor) {
        this.requireRoleInterceptor = requireRoleInterceptor;
        this.internalServiceAuthInterceptor = internalServiceAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalServiceAuthInterceptor)
            .addPathPatterns("/internal/knowledge/**");
        registry.addInterceptor(requireRoleInterceptor);
    }
}

