package com.dameokja.backend.global.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "다먹자 API",
                version = "v1",
                description = "다먹자 백엔드 REST API 명세"
        )
)
public class OpenApiConfig {
}
