package com.dameokja.backend.auth.presentation;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(SwaggerSecurityWebTest.DocumentationController.class)
class SwaggerSecurityWebTest extends SecurityWebTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"/swagger-ui.html", "/swagger-ui/index.html", "/swagger-ui/swagger-ui.css",
            "/v3/api-docs", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml"})
    void documentationIsPublicWithoutAccessToken(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/swagger-ui.html", "/swagger-ui/index.html", "/swagger-ui/swagger-ui.css",
            "/v3/api-docs", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml"})
    void documentationIgnoresInvalidAccessToken(String path) throws Exception {
        mockMvc.perform(get(path).cookie(new Cookie("accessToken", "invalid")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/test/me", "/swagger-ui-private", "/v3/api-docs-private"})
    void otherPathsStillRequireAuthentication(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @RestController
    static class DocumentationController {
        @GetMapping({"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml"})
        String documentation() { return "documentation"; }
    }
}
