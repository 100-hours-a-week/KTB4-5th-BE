package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisRequestedPayload;
import com.dameokja.backend.analysis.support.AnalysisDatabaseContractTestSupport;
import com.dameokja.backend.analysis.support.AnalysisDatabaseJobFixture;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AnalysisJobSubmitWebTest extends AnalysisDatabaseContractTestSupport {
    private static final String BODY = "{\"imageObjectKeys\":[\"first\",\"second\"]}";
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired JwtProvider jwtProvider;
    @Autowired ImageUploadMetadataStore imageUploadMetadataStore;
    @Autowired AnalysisJobStateAccess analysisJobStateAccess;
    @Autowired AnalysisDatabaseJobFixture analysisDatabaseJobFixture;
    @Autowired ObjectMapper objectMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        clock.set("2026-10-08T01:00:00Z");
        analysisDatabaseJobFixture.ensureUser(7L);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        imageUploadMetadataStore.save(7L, ImageUploadPurpose.ANALYSIS, "first", "ab".repeat(32));
        imageUploadMetadataStore.save(7L, ImageUploadPurpose.ANALYSIS, "second", "cd".repeat(32));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"AUTO", "RECEIPT"})
    void returnsAcceptedWithQueuedBackendJobWithoutCallingAi(String inputHint) throws Exception {
        Cookie cookie = csrf();
        String body = inputHint == null ? BODY : BODY.replace("}", ",\"inputHint\":\"" + inputHint + "\"}");
        String response = mockMvc.perform(post("/api/v1/image-analyses").cookie(cookie, accessToken())
                        .header("X-XSRF-TOKEN", cookie.getValue()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("IMAGE-202-001"))
                .andExpect(jsonPath("$.message").value("이미지 인식 시작"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.submittedAt").value("2026-10-08T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.expiresAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.pollAfterMs").value(1000)).andReturn().getResponse().getContentAsString();
        JsonNode jsonNode = objectMapper.readTree(response).path("data");
        assertThat(jsonNode.size()).isEqualTo(5);
        AnalysisJob analysisJob = analysisJobStateAccess.findOwned(jsonNode.path("analysisId").asString(), 7L);
        assertThat(analysisJob.analysisImages()).extracting(AnalysisImage::aiAnalysisId).containsOnlyNulls();
        assertCommittedRequestEvents();
        verifyNoInteractions(analysisKafkaPublisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}", "{\"imageObjectKeys\":null}", "{\"imageObjectKeys\":[]}",
            "{\"imageObjectKeys\":[null]}", "{\"imageObjectKeys\":[\" \" ]}", "{\"imageObjectKeys\":\"first\"}",
            "{\"imageObjectKeys\":[\"first\"],\"inputHint\":\"UNKNOWN\"}"})
    void rejectsMalformedRequestsBeforeCallingAi(String body) throws Exception {
        Cookie cookie = csrf();
        mockMvc.perform(post("/api/v1/image-analyses").cookie(cookie, accessToken()).header("X-XSRF-TOKEN", cookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
        assertNothingAccepted();
        verifyNoInteractions(analysisKafkaPublisher);
    }

    @ParameterizedTest
    @CsvSource({"LOGIN,401,GLOBAL-401-001", "CSRF,403,COMMON-403-CSRF-001", "MISMATCH,403,COMMON-403-CSRF-001"})
    void rejectsMissingLoginOrInvalidCsrf(String missing, int status, String code) throws Exception {
        Cookie cookie = csrf();
        Cookie[] cookies = missing.equals("LOGIN") ? new Cookie[]{cookie} : missing.equals("CSRF") ? new Cookie[]{accessToken()} : new Cookie[]{cookie, accessToken()};
        mockMvc.perform(post("/api/v1/image-analyses").cookie(cookies).header("X-XSRF-TOKEN", missing.equals("MISMATCH") ? "wrong" : cookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
        assertNothingAccepted();
        verifyNoInteractions(analysisKafkaPublisher);
    }

    @Test
    void rejectsAnotherUsersUploadBeforeCallingAi() throws Exception {
        imageUploadMetadataStore.save(8L, ImageUploadPurpose.ANALYSIS, "second", "cd".repeat(32));
        Cookie cookie = csrf();
        mockMvc.perform(post("/api/v1/image-analyses").cookie(cookie, accessToken()).header("X-XSRF-TOKEN", cookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("IMAGE-422-001"));
        assertNothingAccepted();
        verifyNoInteractions(analysisKafkaPublisher);
    }

    @Test
    void doesNotReturnAcceptedOrLeavePartialWorkWhenAnOutboxInsertFails() throws Exception {
        jdbcTemplate.execute("CREATE TRIGGER fail_submission_outbox BEFORE INSERT ON analysis_outbox FOR EACH ROW BEGIN "
                + "IF JSON_UNQUOTE(JSON_EXTRACT(NEW.message_body, '$.payload.image.objectKey')) = 'second' "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'submission-rollback'; END IF; END");
        Cookie csrfCookie = csrf();
        mockMvc.perform(post("/api/v1/image-analyses").cookie(csrfCookie, accessToken()).header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().is5xxServerError());
        assertNothingAccepted();
        verifyNoInteractions(analysisKafkaPublisher);
    }

    @AfterEach
    void dropFailureTrigger() { jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_submission_outbox"); }

    private void assertCommittedRequestEvents() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_states", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_requests", Integer.class)).isEqualTo(2);
        List<EventEnvelope<AnalysisRequestedPayload>> analysisRequestedEvents = jdbcTemplate.query("SELECT message_body FROM analysis_outbox ORDER BY analysis_outbox_id",
                (resultSet, rowNumber) -> objectMapper.readValue(resultSet.getString(1), new TypeReference<EventEnvelope<AnalysisRequestedPayload>>() {}));
        assertThat(analysisRequestedEvents).extracting(event -> event.payload().image().objectKey()).containsExactly("first", "second");
        assertThat(analysisRequestedEvents).allSatisfy(event -> assertThat(event.payload().userId()).isEqualTo(7L));
        assertThat(jdbcTemplate.queryForList("SELECT message_key FROM analysis_outbox", String.class)).containsOnly("7");
        assertThat(jdbcTemplate.queryForList("SELECT status FROM analysis_outbox", String.class)).containsOnly("PENDING");
    }

    private void assertNothingAccepted() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_states", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_requests", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_outbox", Integer.class)).isZero();
    }

    private Cookie csrf() throws Exception { return mockMvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN"); }
    private Cookie accessToken() { return new Cookie("accessToken", jwtProvider.createAccessToken(7L, UserRole.USER)); }

}
