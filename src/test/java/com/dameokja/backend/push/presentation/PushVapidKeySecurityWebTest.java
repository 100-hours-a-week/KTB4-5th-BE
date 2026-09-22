package com.dameokja.backend.push.presentation;

import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.SecurityConfig;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.push.domain.VapidKeyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(PushVapidKeySecurityWebTest.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "jwt.secret=dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=",
        "jwt.access-token-expiration=15m", "jwt.refresh-token-expiration=2d",
        "auth.cookie.secure=true"
})
class PushVapidKeySecurityWebTest {
    @Autowired WebApplicationContext context;
    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // AuthenticationRoutes.PUBLIC 등록을 실제 Security 필터 체인으로 검증한다(로그인 쿠키 없이 호출).
    @Test
    void isReachableWithoutLogin() throws Exception {
        mockMvc.perform(get("/api/v1/push-subscriptions/vapid-public-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PUSH-200-001"))
                .andExpect(jsonPath("$.data.vapidPublicKey").value("test-vapid-public-key"))
                .andExpect(jsonPath("$.data.vapidKeyVersion").value("test-v1"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, SecurityErrorHandler.class, JwtProvider.class,
            GlobalExceptionHandler.class, PushVapidKeyController.class})
    static class WebConfiguration {
        @Bean
        static org.springframework.core.convert.ConversionService conversionService() {
            return new org.springframework.boot.convert.ApplicationConversionService();
        }
        @Bean
        VapidKeyProperties vapidKeyProperties() {
            return new VapidKeyProperties("test-vapid-public-key", "test-v1");
        }
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
        @Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }
}
