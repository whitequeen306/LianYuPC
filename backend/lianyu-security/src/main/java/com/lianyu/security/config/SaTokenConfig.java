package com.lianyu.security.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Value("${lianyu.api-docs.enabled:true}")
    private boolean apiDocsEnabled;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
                    var router = SaRouter.match("/api/**")
                            .notMatch("/api/auth/login", "/api/auth/register", "/api/auth/captcha")
                            .notMatch("/api/public/**");
                    if (apiDocsEnabled) {
                        router.notMatch("/doc.html", "/v3/api-docs/**", "/webjars/**", "/swagger-ui.html",
                                "/swagger-ui/**");
                    }
                    router.check(r -> StpUtil.checkLogin());
                }))
                .addPathPatterns("/**");
    }
}
