package com.dameokja.backend.analysis.presentation;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dameokja.backend.analysis.application.registration.AnalysisIngredientService;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.ingredient.application.create.IngredientWriteResult;
import com.dameokja.backend.support.RedisTestSupport;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

@org.springframework.test.context.bean.override.mockito.MockitoBean(types = {com.dameokja.backend.global.security.OAuthSecurityConfig.class,
        com.dameokja.backend.user.application.access.UserAccessService.class})
@SpringJUnitConfig({AnalysisJobListWebTest.WebConfiguration.class, AnalysisIngredientWebTest.Configuration.class})
@WebAppConfiguration
@TestPropertySource(properties = {"jwt.secret=dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=", "jwt.access-token-expiration=15m", "jwt.refresh-token-expiration=2d"})
@ExtendWith(RedisTestSupport.class)
class AnalysisIngredientWebTest {
    private static final String PATH = "/api/v1/refrigerators/1/ingredients/from-analysis";
    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired AnalysisIngredientService service;

    @Test
    void returnsCreatedAndEnforcesLoginCsrfAndNestedValidation() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(context.getBean(java.time.Clock.class).instant()).thenReturn(java.time.Instant.now());
        Cookie token = new Cookie("accessToken", jwt.createAccessToken(7L, UserRole.USER));
        when(service.create(eq(7L), eq(1L), any())).thenReturn(new IngredientWriteResult(List.of(), 1, 0, 30, 100));
        mvc.perform(post(PATH).with(csrf()).cookie(token).contentType("application/json").content(AnalysisIngredientApiExamples.REQUEST))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("INGREDIENT-201-001"))
                .andExpect(jsonPath("$.data.createdCount").value(1));
        mvc.perform(post(PATH).with(csrf()).contentType("application/json").content(AnalysisIngredientApiExamples.REQUEST)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).cookie(token).contentType("application/json").content(AnalysisIngredientApiExamples.REQUEST)).andExpect(status().isForbidden());
        for (String invalid : List.of("{\"analysisId\":\"job\",\"images\":[null]}",
                AnalysisIngredientApiExamples.REQUEST.replace("\"quantity\":10", "\"quantity\":1.5").replace("\"name\":\"달걀\"", "\"name\":\"\""))) {
            mvc.perform(post(PATH).with(csrf()).cookie(token).contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
        }
        verify(service, times(1)).create(eq(7L), eq(1L), any());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(AnalysisIngredientController.class)
    static class Configuration {
        @Bean
        AnalysisIngredientService analysisIngredientService() { return mock(AnalysisIngredientService.class); }
    }
}
