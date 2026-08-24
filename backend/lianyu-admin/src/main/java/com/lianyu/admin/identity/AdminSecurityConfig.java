package com.lianyu.admin.identity;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminSecurityConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            StpUtil.checkLogin();
            String loginId = StpUtil.getLoginIdAsString();
            if (!loginId.startsWith("admin:")) throw new SecurityException("管理员登录已失效");
        })).addPathPatterns("/api/admin/v1/**").excludePathPatterns("/api/admin/v1/auth/login");
    }
}
