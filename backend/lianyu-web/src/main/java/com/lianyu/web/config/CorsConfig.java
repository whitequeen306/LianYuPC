package com.lianyu.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS 配置。
 * <p>
 * 生产环境由 Nginx 统一处理 CORS + 反向代理，前端不直连后端。
 * 此配置仅用于本地开发（IDE 直连 localhost:5173 → localhost:8080）。
 * </p>
 */
@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 生产环境由 Nginx 兜底，此处仅放行本地开发域名
        String[] origins = allowedOrigins.split(",");
        for (int i = 0; i < origins.length; i++) {
            origins[i] = origins[i].trim();
        }
        config.setAllowedOriginPatterns(List.of(origins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Content-Type",
                "lianyu-token",
                "X-Trace-Id",
                "X-LianYu-Output-Language",
                "X-LianYu-Ui-Language"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        // Public media (chat TTS etc.): Electron file:// / null Origin needs wildcard ACAO.
        CorsConfiguration publicFiles = new CorsConfiguration();
        publicFiles.setAllowedOriginPatterns(List.of("*"));
        publicFiles.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
        publicFiles.setAllowedHeaders(List.of("*"));
        publicFiles.setAllowCredentials(false);
        publicFiles.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // More specific path first — public files must not require credentialed origin match.
        source.registerCorsConfiguration("/api/public/files/**", publicFiles);
        // 仅 REST /api；/ws 由 WebSocketConfig.setAllowedOriginPatterns 单独处理
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
