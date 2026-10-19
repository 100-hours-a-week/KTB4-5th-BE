package com.dameokja.backend.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.dameokja.backend.analysis.application.AnalysisFeedbackQueue;
import com.dameokja.backend.analysis.application.AnalysisFeedbackProperties;
import com.dameokja.backend.analysis.application.AnalysisFeedbackSender;
import com.dameokja.backend.support.MySqlJpaTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Import({AnalysisFeedbackQueue.class, AnalysisFeedbackDeliveryTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnalysisFeedbackDeliveryTest extends MySqlJpaTest {
    private static final String BASE = System.getenv().getOrDefault("FEEDBACK_SIM_URL", "http://ai.example.com");
    private static final String BODY = """
            {"itemId":"item-1","action":"CORRECTED","consentForModelImprovement":true,
             "confirmed":{"name":"우유","category":"DAIRY","storageType":"REFRIGERATED","measureType":"COUNT",
                          "quantity":2,"weightUnit":"NONE","expirationDate":"2026-10-20","expirationSource":"USER_CONFIRMED"}}
            """;
    @Autowired AnalysisFeedbackQueue queue;
    @Autowired JdbcTemplate jdbc;
    private AnalysisFeedbackSender sender;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        AiAnalysisProperties properties = new AiAnalysisProperties(URI.create(BASE), "test-key", Duration.ofSeconds(1), Duration.ofSeconds(2));
        org.springframework.web.client.RestClient.Builder builder = new AiAnalysisConfig().restClientBuilder(properties, HttpClient.newHttpClient());
        if (System.getenv("FEEDBACK_SIM_URL") == null) { server = MockRestServiceServer.bindTo(builder).build(); }
        sender = new AnalysisFeedbackSender(queue, new AiAnalysisClient(builder.build()), JsonMapper.builder().build());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM analysis_feedbacks");
        if (server != null) { server.verify(); }
    }

    @ParameterizedTest
    @CsvSource({"success, 201, DONE", "unavailable, 503, PENDING", "busy, 429, PENDING", "rejected, 404, FAILED"})
    void recordsDeliveryOutcomeAndRetriesOnlyRetryableResponses(String id, int status, String outcome) {
        ObjectMapper mapper = JsonMapper.builder().build();
        queue.enqueue(id, mapper.readValue(BODY, AiAnalysisFeedbackRequest.class));
        expect("/health/ready", 200, "{}");
        expect("/ai/v1/analyses/" + id + "/feedback", status, "{\"feedbackId\":\"feedback-1\",\"status\":\"RECORDED\"}");
        sender.sendDue();
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_feedbacks", String.class)).isEqualTo(outcome);
        if (outcome.equals("PENDING")) {
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM analysis_feedbacks", Integer.class)).isEqualTo(1);
            jdbc.update("UPDATE analysis_feedbacks SET ai_analysis_id = 'success', next_attempt_at = '2000-01-01 00:00:00'");
            if (server != null) {
                server.verify();
                server.reset();
            }
            expect("/health/ready", 200, "{}");
            expect("/ai/v1/analyses/success/feedback", 201, "{\"feedbackId\":\"feedback-1\",\"status\":\"RECORDED\"}");
            sender.sendDue();
            assertThat(jdbc.queryForObject("SELECT status FROM analysis_feedbacks", String.class)).isEqualTo("DONE");
        }
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "FEEDBACK_SIM_URL", matches = ".+")
    void doesNotLeaseWhenAiIsNotReady() {
        queue.enqueue("success", JsonMapper.builder().build().readValue(BODY, AiAnalysisFeedbackRequest.class));
        expect("/health/ready", 503, "{}");
        sender.sendDue();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM analysis_feedbacks", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM analysis_feedbacks", String.class)).isEqualTo("PENDING");
    }

    private void expect(String path, int status, String body) {
        if (server != null) {
            server.expect(requestTo(BASE + path)).andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AnalysisFeedbackProperties.class)
    static class Config {
        @Bean
        ObjectMapper feedbackMapper() { return JsonMapper.builder().build(); }
    }
}
