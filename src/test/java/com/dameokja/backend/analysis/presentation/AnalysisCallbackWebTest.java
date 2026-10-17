package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.application.AnalysisCallbackService;
import com.dameokja.backend.analysis.application.AnalysisCompletionApplier;
import com.dameokja.backend.analysis.application.AnalysisCompletionNotifier;
import com.dameokja.backend.analysis.application.AnalysisCompletionValidator;
import com.dameokja.backend.analysis.application.AnalysisDispatchUpdater;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AnalysisCallbackParking;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisProperties;
import com.dameokja.backend.global.exception.GlobalExceptionHandler;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.push.application.PushAnalysisCreationService;
import com.dameokja.backend.notification.application.AnalysisFinishedEventService;
import com.dameokja.backend.notification.application.NotificationStreamService;
import com.dameokja.backend.support.RedisTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RedisTestSupport.class)
@SpringJUnitConfig(AnalysisCallbackWebTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = {"ai.analysis.api-key=local-ai-test", "ai.analysis.base-url=http://127.0.0.1:18081",
        "ai.analysis.connect-timeout=1s", "ai.analysis.read-timeout=3s"})
class AnalysisCallbackWebTest {
    private static final String PATH = "/api/v1/internal/image-analyses/callback";
    private static final String BODY = """
            {"analysisId":"ai-first","status":"COMPLETED","completedAt":"2026-10-14T01:00:00Z",
             "result":{"documentType":"PRODUCT","items":[]},"error":null}
            """;
    @Autowired WebApplicationContext context;
    @Autowired AnalysisJobStore store;
    @Autowired PushAnalysisCreationService pushes;
    @Autowired AnalysisCallbackParking analysisCallbackParking;
    private MockMvc mvc;
    private AnalysisJob job;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(pushes);
        job = store.create(7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))));
        store.register(job.id(), 7L, job.analysisImages().getFirst().requestId(), "ai-first");
    }

    @Test
    void acceptsProductAndDuplicateWithoutSessionOrCsrf() throws Exception {
        send(BODY, "local-ai-test", 200);
        String original = RedisTestSupport.redisTemplate().opsForValue().get("analysis:job:" + job.id());
        send(BODY, "local-ai-test", 200);
        assertThat(RedisTestSupport.redisTemplate().opsForValue().get("analysis:job:" + job.id())).isEqualTo(original);
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion().result().documentType()).isEqualTo(AnalysisDocumentType.PRODUCT);
    }

    @Test
    void acceptsFailureAndRejectsConflictingResult() throws Exception {
        send(BODY, "local-ai-test", 200);
        send(BODY.replace("PRODUCT", "RECEIPT"), "local-ai-test", 409);
        RedisTestSupport.redisTemplate().opsForValue().set("analysis:job:" + job.id(), JsonMapper.builder().build().writeValueAsString(job));
        store.register(job.id(), 7L, job.analysisImages().getFirst().requestId(), "ai-first");
        String failed = BODY.replace("COMPLETED", "FAILED").replace("{\"documentType\":\"PRODUCT\",\"items\":[]}", "null")
                .replace("\"error\":null", "\"error\":{\"code\":\"MODEL_UNAVAILABLE\",\"message\":\"failed\",\"retryable\":true}");
        send(failed, "local-ai-test", 200);
        verify(pushes, times(2)).create(eq(job.id()), eq(7L), any(OffsetDateTime.class));
        assertThat(store.findOwned(job.id(), 7L).notificationPending()).isFalse();
    }

    @Test
    void createsCompletionPushOnceWhenTheLastResultIsSaved() throws Exception {
        send(BODY, "local-ai-test", 200);
        send(BODY, "local-ai-test", 200);
        verify(pushes, times(1)).create(eq(job.id()), eq(7L), any(OffsetDateTime.class));
        assertThat(store.findOwned(job.id(), 7L).notificationPending()).isFalse();
    }

    @Test
    void keepsSavedResultAndRetriesPushCreationOnTheNextCallback() throws Exception {
        doThrow(new DataAccessResourceFailureException("db")).doNothing().when(pushes).create(any(), any(), any());
        send(BODY, "local-ai-test", 503);
        AnalysisJob saved = store.findOwned(job.id(), 7L);
        assertThat(saved.analysisImages().getFirst().completion()).isNotNull();
        assertThat(saved.notificationPending()).isTrue();
        send(BODY, "local-ai-test", 200);
        verify(pushes, times(2)).create(eq(job.id()), eq(7L), any(OffsetDateTime.class));
        assertThat(store.findOwned(job.id(), 7L).notificationPending()).isFalse();
    }

    @Test
    void preservesNullableValuesEstimatedDatesAndStructuredReasons() throws Exception {
        String item = """
                {"itemId":"item-1","displayStatus":"NEEDS_REVIEW","name":null,"category":null,"storageType":null,
                 "measureType":"COUNT","quantity":null,"weight":{"value":null,"unit":"NONE"},
                 "expiration":{"date":null,"dateType":"ESTIMATED_CONSUMPTION_WINDOW","estimatedRange":{"from":"2026-10-15","to":"2026-10-18"}},
                 "reviewReasons":[{"field":"QUANTITY","code":"QUANTITY_DEFAULTED","messages":["개수를 확인해주세요."]}]}
                """;
        send(BODY.replace("[]", "[" + item + "]"), "local-ai-test", 200);
        String saved = RedisTestSupport.redisTemplate().opsForValue().get("analysis:job:" + job.id());
        assertThat(saved).contains("ESTIMATED_CONSUMPTION_WINDOW", "QUANTITY_DEFAULTED", "개수를 확인해주세요.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"result\":{\"documentType\":\"PRODUCT\",\"items\":[]},", ",\"error\":null"})
    void rejectsMissingNullableEnvelopeFields(String missing) throws Exception { send(BODY.replace(missing, ""), "local-ai-test", 400); }

    @Test
    void rejectsFailureWithoutRetryableInsteadOfTreatingItAsFalse() throws Exception {
        String failed = BODY.replace("COMPLETED", "FAILED").replace("{\"documentType\":\"PRODUCT\",\"items\":[]}", "null")
                .replace("\"error\":null", "\"error\":{\"code\":\"MODEL_UNAVAILABLE\",\"message\":\"failed\"}");
        send(failed, "local-ai-test", 400);
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PROCESSING", "PARTIALLY_COMPLETED"})
    void rejectsNonTerminalStatus(String status) throws Exception { send(BODY.replace("COMPLETED", status), "local-ai-test", 400); }

    @Test
    void rejectsMalformedAndUnauthorizedCallbacks() throws Exception {
        send(BODY, "wrong", 401);
        send("{}", "local-ai-test", 400);
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isNull();
    }

    @Test
    void acceptsAndParksACallbackThatArrivesBeforeItsAiIdIsRegistered() throws Exception {
        send(BODY.replace("ai-first", "ai-early"), "local-ai-test", 200);
        assertThat(analysisCallbackParking.find("ai-early")).isPresent();
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"quantity\":0", "\"weight\":null", "\"reviewReasons\":null", "\"category\":\"INVALID\"",
            "\"expiration\":{\"date\":\"2026-10-15\",\"dateType\":\"SELL_BY\",\"estimatedRange\":null}",
            "\"storageType\":\"INVALID\"", "\"measureType\":\"INVALID\"", "\"unit\":\"INVALID\"", "\"dateType\":\"INVALID\"",
            "\"dateType\":null"})

    void rejectsInvalidItemValues(String replacement) throws Exception {
        String item = """
                {"itemId":"item-1","displayStatus":"NEEDS_REVIEW","name":null,"category":null,"storageType":null,
                 "measureType":"COUNT","quantity":null,"weight":{"value":null,"unit":"NONE"},
                 "expiration":{"date":null,"dateType":"UNKNOWN","estimatedRange":null},"reviewReasons":[]}
                """;
        String field = replacement.substring(0, replacement.indexOf(':'));
        String original = switch (field) {
            case "\"weight\"" -> "\"weight\":{\"value\":null,\"unit\":\"NONE\"}";
            case "\"reviewReasons\"" -> "\"reviewReasons\":[]";
            case "\"expiration\"" -> "\"expiration\":{\"date\":null,\"dateType\":\"UNKNOWN\",\"estimatedRange\":null}";
            case "\"unit\"" -> "\"unit\":\"NONE\"";
            case "\"measureType\"" -> "\"measureType\":\"COUNT\"";
            case "\"dateType\"" -> "\"dateType\":\"UNKNOWN\"";

            default -> field + ":null";
        };
        send(BODY.replace("[]", "[" + item.replace(original, replacement) + "]"), "local-ai-test", 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"WEIGHT\""})
    void preservesUnrecognizedMeasurementValues(String measureType) throws Exception {
        String item = """
                {"itemId":"item-1","displayStatus":"UNRECOGNIZED","name":null,"category":null,"storageType":null,
                 "measureType":%s,"quantity":null,"weight":{"value":null,"unit":null},
                 "expiration":{"date":null,"dateType":"UNKNOWN","estimatedRange":null},"reviewReasons":[]}
                """.formatted(measureType);
        send(BODY.replace("[]", "[" + item + "]"), "local-ai-test", 200);
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion().result().items().getFirst().weight().unit()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"INVALID\"", "null"})
    void rejectsInvalidOrMissingDocumentType(String documentType) throws Exception {
        send(BODY.replace("\"documentType\":\"PRODUCT\"", "\"documentType\":" + documentType), "local-ai-test", 400);
    }

    private void send(String body, String key, int expected) throws Exception {
        mvc.perform(post(PATH).header("X-Internal-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableConfigurationProperties(AiAnalysisProperties.class)
    @Import({AnalysisCallbackController.class, AnalysisCallbackSecurityConfig.class, AnalysisCallbackService.class,
            AnalysisCompletionApplier.class, AnalysisCompletionValidator.class, AnalysisCompletionNotifier.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean
        PushAnalysisCreationService pushes() {
            return mock(PushAnalysisCreationService.class);
        }

        @Bean
        AnalysisDispatchUpdater dispatches() { return mock(AnalysisDispatchUpdater.class); }

        @Bean
        AnalysisFinishedEventService events() { return mock(AnalysisFinishedEventService.class); }

        @Bean
        NotificationStreamService connections() { return mock(NotificationStreamService.class); }

        @Bean
        AnalysisCallbackParking parking() {
            return new AnalysisCallbackParking(Duration.ofMinutes(10), RedisTestSupport.redisTemplate(), JsonMapper.builder().build());
        }

        @Bean
        AnalysisJobStore store() {
            return new AnalysisJobStore(Clock.system(BusinessTime.ZONE), Duration.ofHours(1), RedisTestSupport.redisTemplate(), JsonMapper.builder().build());
        }
    }
}
