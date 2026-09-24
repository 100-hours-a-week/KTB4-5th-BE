package com.dameokja.backend.push.presentation;

import com.dameokja.backend.auth.presentation.CsrfController;
import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.SecurityConfig;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.push.application.PushSubscriptionResult;
import com.dameokja.backend.push.application.PushSubscriptionService;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(PushSubscriptionSecurityWebTest.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "jwt.secret=dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=",
        "jwt.access-token-expiration=15m", "jwt.refresh-token-expiration=2d",
        "auth.cookie.secure=true"
})
class PushSubscriptionSecurityWebTest {
    private static final String BODY = """
            {"endpoint": "https://push.example.com/1",
             "keys": {"p256dh": "p256dh-key", "auth": "auth-secret"}}
            """;

    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwtProvider;
    @Autowired PushSubscriptionService pushSubscriptionService;
    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        reset(pushSubscriptionService);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private Cookie csrf() throws Exception {
        return mockMvc.perform(get("/api/v1/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private Cookie accessToken() {
        return new Cookie("accessToken", jwtProvider.createAccessToken(7L, UserRole.USER));
    }

    @Test
    void registerReturnsCreatedWithLocationForNewSubscription() throws Exception {
        when(pushSubscriptionService.register(7L, "https://push.example.com/1", "p256dh-key",
                "auth-secret")).thenReturn(new PushSubscriptionResult(501L, true));
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/push-subscriptions").cookie(csrf, accessToken())
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/push-subscriptions/501"))
                .andExpect(jsonPath("$.code").value("PUSH-201-001"))
                .andExpect(jsonPath("$.data.subscriptionId").value("501"));
    }

    @Test
    void registerReturnsOkWithoutLocationForRenewedSubscription() throws Exception {
        when(pushSubscriptionService.register(7L, "https://push.example.com/1", "p256dh-key",
                "auth-secret")).thenReturn(new PushSubscriptionResult(501L, false));
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/push-subscriptions").cookie(csrf, accessToken())
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.code").value("PUSH-200-002"));
    }

    @Test
    void registerRequiresLogin() throws Exception {
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/push-subscriptions").cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerRequiresCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/push-subscriptions").cookie(accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
    }

    @Test
    void unregisterReturnsNoContent() throws Exception {
        Cookie csrf = csrf();
        mockMvc.perform(delete("/api/v1/push-subscriptions/501").cookie(csrf, accessToken())
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    void unregisterRequiresLogin() throws Exception {
        Cookie csrf = csrf();
        mockMvc.perform(delete("/api/v1/push-subscriptions/501").cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, SecurityErrorHandler.class, JwtProvider.class,
            GlobalExceptionHandler.class, CsrfController.class, PushSubscriptionController.class})
    static class WebConfiguration {
        @Bean
        static org.springframework.core.convert.ConversionService conversionService() {
            return new org.springframework.boot.convert.ApplicationConversionService();
        }
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
        @Bean
        PushSubscriptionService pushSubscriptionService() {
            return mock(PushSubscriptionService.class);
        }
    }
}
