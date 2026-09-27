package com.dameokja.backend.user.presentation;

import com.dameokja.backend.auth.presentation.AuthCookies;
import com.dameokja.backend.auth.presentation.CsrfController;
import com.dameokja.backend.auth.presentation.CsrfTokenRotator;
import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.SecurityConfig;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.user.application.UserSignupService;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.ConversionService;
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringJUnitConfig(UserSecurityWebTestSupport.WebConfiguration.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "jwt.secret=dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=",
        "jwt.access-token-expiration=15m", "jwt.refresh-token-expiration=2d",
        "auth.cookie.secure=true"
})
abstract class UserSecurityWebTestSupport {
    @Autowired WebApplicationContext context;
    @Autowired UserSignupService userSignupService;
    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        reset(userSignupService);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    Cookie csrf() throws Exception {
        return mockMvc
                .perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, SecurityErrorHandler.class, JwtProvider.class,
            AuthCookies.class, CsrfTokenRotator.class, UserController.class,
            CsrfController.class, GlobalExceptionHandler.class})
    static class WebConfiguration {
        @Bean
        static ConversionService conversionService() { return new ApplicationConversionService(); }
        @Bean
        ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean
        Clock clock() { return Clock.systemUTC(); }
        @Bean
        UserSignupService userSignupService() { return mock(UserSignupService.class); }
    }
}
