package com.lianyu.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI lianYuOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LianYu-PC API")
                        .version("0.1.0")
                        .description("LianYu PC 端 REST API")
                        .license(new License().name("Private").url("https://github.com")));
    }
}
