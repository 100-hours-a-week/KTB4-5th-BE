package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitRequest;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitResponse;
import com.dameokja.backend.analysis.support.AnalysisContractTestSupport;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class AnalysisRequestRetryTest extends AnalysisContractTestSupport {
    private final BlockingQueue<AiImageAnalysisSubmitRequest> requests = new LinkedBlockingQueue<>();
    private final AtomicBoolean aiRecovered = new AtomicBoolean();

    @ParameterizedTest
    @ValueSource(strings = {"UNAVAILABLE", "TIMEOUT", "BUSY"})
    void retainsTheAcceptedAnalysisAndCompletesTheSameRequestAfterAiRecovers(String failure) throws Exception {
        failUntilRecovered(failure);
        Long userId = ownerFixture("재시도계약").userId();
        String analysisId = accept(userId);
        AiImageAnalysisSubmitRequest firstRequest = receivedRequest();
        queryAnalysis(userId, analysisId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(analysisId)).andExpect(jsonPath("$.data.status").value("QUEUED"));

        aiRecovered.set(true);
        AiImageAnalysisSubmitRequest retriedRequest = retryAndReceive();

        assertThat(retriedRequest).isEqualTo(firstRequest);
        await().atMost(WAIT_LIMIT).untilAsserted(() -> queryAnalysis(userId, analysisId)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING")));
        completeAnalysis(AI_ANALYSIS_ID, "PRODUCT").andExpect(status().isOk());
        queryAnalysis(userId, analysisId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(analysisId)).andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.results[0].items[0].name").value("우유"));
    }

    @Test
    void exposesATerminalFailureWhenAiRemainsUnavailableThroughTheAttemptLimit() throws Exception {
        failUntilRecovered("UNAVAILABLE");
        Long userId = ownerFixture("실패계약").userId();
        String analysisId = accept(userId);
        AiImageAnalysisSubmitRequest firstRequest = receivedRequest();
        for (int attempt = 1; attempt < analysisAttemptLimit(); attempt++) {
            queryAnalysis(userId, analysisId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"));
            assertThat(retryAndReceive()).isEqualTo(firstRequest);
        }

        queryAnalysis(userId, analysisId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value(analysisId)).andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.results[0].error.code").value("ANALYSIS_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.results[0].error.retryable").value(true))
                .andExpect(jsonPath("$.data.pollAfterMs").isEmpty());
    }

    private void failUntilRecovered(String failure) {
        doAnswer(invocation -> {
            AiImageAnalysisSubmitRequest request = invocation.getArgument(0);
            if (!aiRecovered.get()) {
                RuntimeException exception = failure(failure);
                requests.add(request);
                throw exception;
            }
            requests.add(request);
            return new AiImageAnalysisSubmitResponse(AI_ANALYSIS_ID, "QUEUED", null, 1000);
        }).when(ai).submit(any());
    }

    private RuntimeException failure(String failure) {
        return switch (failure) {
            case "TIMEOUT" -> new ResourceAccessException("AI read timeout");
            case "BUSY" -> new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
            default -> new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
        };
    }

    private String accept(Long userId) throws Exception {
        String accepted = submitImage(userId, "PRODUCT").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED")).andReturn().getResponse().getContentAsString();
        return mapper.readTree(accepted).path("data").path("analysisId").asString();
    }

    private AiImageAnalysisSubmitRequest retryAndReceive() throws InterruptedException {
        await().atMost(WAIT_LIMIT).until(() -> {
            retryDueAnalyses();
            return !requests.isEmpty();
        });
        return receivedRequest();
    }

    private AiImageAnalysisSubmitRequest receivedRequest() throws InterruptedException {
        AiImageAnalysisSubmitRequest request = requests.poll(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(request).as("AI에 실제 전달된 분석 요청").isNotNull();
        return request;
    }
}
