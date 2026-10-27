package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AnalysisCallbackParking;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.support.RedisTestSupport;
import com.dameokja.backend.push.application.PushAnalysisCreationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ExtendWith(RedisTestSupport.class)
@SpringJUnitConfig(AnalysisCallbackWebTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = {"ai.api-key=local-ai-test", "ai.base-url=http://127.0.0.1:18081",
        "ai.connect-timeout=1s", "ai.read-timeout=3s"})
class AnalysisCallbackIdentifierWebTest {
    private static final String BODY = """
            {"analysisId":"ai-first","status":"COMPLETED","completedAt":"2026-10-14T01:00:00Z",
             "result":{"documentType":"PRODUCT","items":[]},"error":null}
            """;
    @Autowired WebApplicationContext context;
    @Autowired AnalysisJobStore store;
    @Autowired AnalysisCallbackParking analysisCallbackParking;
    private MockMvc mvc;
    private AnalysisJob job;

    @BeforeEach
    void setup() {
        reset(context.getBean(PushAnalysisCreationService.class));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        job = store.create(7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))));
        store.register(job.id(), 7L, job.analysisImages().getFirst().requestId(), "ai-first");
    }

    @ParameterizedTest
    @CsvSource({"64,200", "65,400"})
    void validatesAiIdsBeforeParkingCallbacks(int length, int expectedStatus) throws Exception {
        String analysisId = "a".repeat(length);
        send(BODY.replace("ai-first", analysisId), "local-ai-test", expectedStatus);
        assertThat(analysisCallbackParking.find(analysisId).isPresent()).isEqualTo(length == 64);
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"64,200", "65,400"})
    void validatesItemIdsBeforeSavingCallbacks(int length, int expectedStatus) throws Exception {
        String item = """
                {"itemId":"%s","displayStatus":"NEEDS_REVIEW","name":null,"category":null,"storageType":null,
                 "measureType":null,"quantity":null,"weight":{"value":null,"unit":null},
                 "expiration":{"date":null,"dateType":"UNKNOWN","estimatedRange":null},"reviewReasons":[]}
                """.formatted("i".repeat(length));
        send(BODY.replace("[]", "[" + item + "]"), "local-ai-test", expectedStatus);
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion() != null).isEqualTo(length == 64);
    }

    private void send(String body, String key, int expected) throws Exception {
        mvc.perform(post("/api/v1/internal/image-analyses/callback").header("X-Internal-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }
}
