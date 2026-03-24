package com.novelanalyzer.config;

import com.novelanalyzer.modules.security.interceptor.RequireRoleInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityWebConfig implements WebMvcConfigurer {

    private final RequireRoleInterceptor requireRoleInterceptor;

    public SecurityWebConfig(RequireRoleInterceptor requireRoleInterceptor) {
        this.requireRoleInterceptor = requireRoleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requireRoleInterceptor);
    }
}

