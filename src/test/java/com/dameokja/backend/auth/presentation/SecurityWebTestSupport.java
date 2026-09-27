package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.auth.infrastructure.CaffeineRefreshSessionStore;
import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.SecurityConfig;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.user.application.AuthenticatedUser;
import com.dameokja.backend.user.application.UserAuthenticationService;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringJUnitConfig(SecurityWebTestSupport.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "jwt.secret=dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=",
        "jwt.access-token-expiration=15m", "jwt.refresh-token-expiration=2d",
        "auth.cookie.secure=true"
})
abstract class SecurityWebTestSupport {
    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwtProvider;
    @Autowired AuthService authService;
    @Autowired UserAuthenticationService userAuthenticationService;
    @Autowired MutableClock clock;
    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        clock.now = Instant.parse("2026-09-20T00:00:00Z");
        reset(userAuthenticationService);
        when(userAuthenticationService.authenticate("user1", "password1"))
                .thenReturn(new AuthenticatedUser(7L, UserRole.USER));
        when(userAuthenticationService.findActive(7L))
                .thenReturn(new AuthenticatedUser(7L, UserRole.USER));
        when(userAuthenticationService.findActiveForUpdate(7L))
                .thenReturn(new AuthenticatedUser(7L, UserRole.USER));
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    Cookie csrf() throws Exception {
        return mockMvc
                .perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, SecurityErrorHandler.class, JwtProvider.class,
            CaffeineRefreshSessionStore.class, AuthService.class, AuthCookies.class,
            CsrfTokenRotator.class, AuthController.class, CsrfController.class,
            GlobalExceptionHandler.class,
            ProtectedController.class})
    static class WebConfiguration {
        @Bean
        static org.springframework.core.convert.ConversionService conversionService() {
            return new org.springframework.boot.convert.ApplicationConversionService();
        }
        @Bean
        MutableClock clock() { return new MutableClock(); }
        @Bean
        ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean
        RefrigeratorAccessService refrigeratorAccessService() {
            return mock(RefrigeratorAccessService.class);
        }
        @Bean
        UserAuthenticationService userAuthenticationService() {
            return mock(UserAuthenticationService.class);
        }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-20T00:00:00Z");
        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override
        public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }

    @TestComponent
    @RestController
    static class ProtectedController {
        @GetMapping("/api/test/me")
        Long me(@CurrentUserId Long userId) { return userId; }
        @GetMapping("/api/test/role")
        String role(org.springframework.security.core.Authentication authentication) {
            return authentication.getAuthorities().iterator().next().getAuthority();
        }
        @GetMapping("/api/test/fail")
        String fail() { throw new IllegalStateException("controller failure"); }
        @PostMapping("/api/test/me")
        Long change(@CurrentUserId Long userId) { return userId; }
    }
}
