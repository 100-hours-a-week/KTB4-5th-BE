package com.dameokja.backend.auth.integration;

import com.dameokja.backend.support.ServiceIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = {"KAFKA_ENV=dev", "spring.kafka.listener.auto-startup=false"})
class ApiDocumentationEnabledIntegrationTest extends ServiceIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void developmentDocumentationIsPublicEvenWithStaleAccessCookie() throws Exception {
        Cookie stale = new Cookie("accessToken", "stale");
        mvc.perform(get("/swagger-ui.html").cookie(stale)).andExpect(status().isFound())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html").cookie(stale)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
        mvc.perform(get("/v3/api-docs").cookie(stale)).andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists()).andExpect(jsonPath("$.paths").isNotEmpty());
        mvc.perform(get("/v3/api-docs.yaml").cookie(stale)).andExpect(status().isOk())
                .andExpect(content().string(containsString("openapi:")));
        mvc.perform(get("/v3/api-docs/swagger-config").cookie(stale)).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
    }
}
