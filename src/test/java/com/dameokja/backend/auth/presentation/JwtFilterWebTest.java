package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.global.security.JwtAuthenticationFilter;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwtFilterWebTest extends SecurityWebTestSupport {
    @Test
    void missingAccessRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/test/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void validAccessInjectsUserIdAndDoesNotLeakContextOrCreateSession() throws Exception {
        String token = jwtProvider.createAccessToken(7L, UserRole.USER);
        MvcResult result = mockMvc.perform(get("/api/test/me").cookie(new Cookie("accessToken", token)))
                .andExpect(status().isOk()).andExpect(content().string("7")).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        mockMvc.perform(get("/api/test/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAndInvalidAccessHaveDistinctErrors() throws Exception {
        String token = jwtProvider.createAccessToken(7L, UserRole.USER);
        clock.advance(Duration.ofMinutes(15));
        mockMvc.perform(get("/api/test/me").cookie(new Cookie("accessToken", token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_EXPIRED"));
        mockMvc.perform(get("/api/test/me").cookie(new Cookie("accessToken", "invalid")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
        mockMvc.perform(get("/api/test/me").cookie(new Cookie("accessToken", "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
    }

    @Test
    void csrfRunsBeforeJwtAndValidCsrfPreservesJwtError() throws Exception {
        mockMvc.perform(post("/api/test/me").cookie(new Cookie("accessToken", "invalid")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/test/me").cookie(csrf, new Cookie("accessToken", "invalid"))
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_INVALID"));
    }

    @Test
    void csrfEndpointIgnoresBadAccessAndFilterIsOnlyInSecurityChain() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/csrf")
                        .cookie(new Cookie("accessToken", "invalid")))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNotNull();
        assertThat(context.getBeansOfType(JwtAuthenticationFilter.class)).hasSize(1);
        FilterRegistrationBean<?> registration = context.getBean("jwtAuthenticationFilterRegistration",
                FilterRegistrationBean.class);
        assertThat(registration.isEnabled()).isFalse();
        assertThat(registration.getFilter())
                .isSameAs(context.getBean(JwtAuthenticationFilter.class));
        FilterChainProxy chain = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        assertThat(chain.getFilters("/api/test/me").stream()
                .filter(JwtAuthenticationFilter.class::isInstance)).hasSize(1);
    }
}
