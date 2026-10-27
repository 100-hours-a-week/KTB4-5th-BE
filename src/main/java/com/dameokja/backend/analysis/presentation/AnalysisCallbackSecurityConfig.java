package com.dameokja.backend.analysis.presentation;

import com.dameokja.backend.global.config.ai.AiClientProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class AnalysisCallbackSecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain analysisCallbackChain(HttpSecurity http, AiClientProperties properties) throws Exception {
        return http.securityMatcher("/api/v1/internal/image-analyses/callback")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new CallbackKeyFilter(properties.apiKey()), UsernamePasswordAuthenticationFilter.class).build();
    }

    private static final class CallbackKeyFilter extends OncePerRequestFilter {
        private final String apiKey;
        private CallbackKeyFilter(String apiKey) { this.apiKey = apiKey; }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String supplied = request.getHeader("X-Internal-API-Key");
            if (apiKey.isBlank() || supplied == null || !MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"IMAGE-401-001\",\"message\":\"서비스 인증에 실패했습니다.\"}");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
