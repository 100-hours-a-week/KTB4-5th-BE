package com.dameokja.backend.auth.integration;

import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = {"KAFKA_ENV=prod", "spring.kafka.listener.auto-startup=false"})
class ApiDocumentationDisabledIntegrationTest extends ServiceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;

    @ParameterizedTest
    @ValueSource(strings = {"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config"})
    void productionDocumentationCannotBeReadByDirectUrl(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(head(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).cookie(new Cookie("accessToken", jwt.createAccessToken(7L, UserRole.USER))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void monitoringAndHealthRemainAvailableToInternalCollectors() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }
}
