package com.dameokja.backend.analysis.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitRequest;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitResponse;
import com.dameokja.backend.analysis.support.AnalysisContractTestSupport;
import com.dameokja.backend.user.domain.User;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AnalysisConcurrencyContractTest extends AnalysisContractTestSupport {
    private final List<AiImageAnalysisSubmitRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger unfinished = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    @BeforeEach
    void observeDistinctAiAnalyses() {
        // 테스트 준비 중에는 자동 전달을 막고, 두 전달 시도를 동시에 시작한다.
        when(ai.ready()).thenReturn(false);
        doAnswer(invocation -> {
            AiImageAnalysisSubmitRequest request = invocation.getArgument(0);
            requests.add(request);
            peak.accumulateAndGet(unfinished.incrementAndGet(), Math::max);
            return new AiImageAnalysisSubmitResponse(aiId(request), "QUEUED", null, 1000);
        }).when(ai).submit(any());
    }

    @Test
    void competingDispatchersRespectAiCapacityAndCompleteTheWaitingImageAfterCapacityIsReleased() throws Exception {
        User user = user("동시접수계약");
        int capacity = analysisSlotLimit();
        List<String> keys = imageKeys(capacity + 1);
        String analysisId = accept(user.getId(), keys);

        dispatchConcurrently();
        assertThat(requests).hasSize(capacity);
        assertThat(unfinished.get()).isEqualTo(capacity);
        assertThat(peak.get()).isLessThanOrEqualTo(capacity);
        queryAnalysis(user.getId(), analysisId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.results[?(@.status == 'QUEUED')]").value(hasSize(1)));

        finish(requests.getFirst());
        dispatchConcurrently();
        assertThat(requests).hasSize(keys.size()).extracting(AiImageAnalysisSubmitRequest::requestId).doesNotHaveDuplicates();
        assertThat(peak.get()).isLessThanOrEqualTo(capacity);
        assertThat(requests).extracting(request -> request.image().objectKey()).containsExactlyInAnyOrderElementsOf(keys);
        for (AiImageAnalysisSubmitRequest request : requests.subList(1, requests.size())) {
            finish(request);
        }
        assertCompleted(user.getId(), analysisId, keys.size());
    }

    @Test
    void concurrentAndRepeatedCallbacksDoNotDuplicateResultsOrFinishTheBatchBeforeEveryImageCompletes() throws Exception {
        User user = user("중복콜백계약");
        String analysisId = accept(user.getId(), imageKeys(2));
        dispatchConcurrently();
        assertThat(requests).hasSize(2);
        String firstAiId = aiId(requests.getFirst());

        callbackConcurrently(firstAiId);
        completeAnalysis(firstAiId, "PRODUCT").andExpect(status().isOk());
        queryAnalysis(user.getId(), analysisId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.results.length()").value(2))
                .andExpect(jsonPath("$.data.results[?(@.status == 'COMPLETED')]").value(hasSize(1)));
        assertSummary(user.getId(), 2, 1);

        completeAnalysis(aiId(requests.getLast()), "PRODUCT").andExpect(status().isOk());
        completeAnalysis(firstAiId, "PRODUCT").andExpect(status().isOk());
        assertCompleted(user.getId(), analysisId, 2);
    }

    private String accept(Long userId, List<String> keys) throws Exception {
        String body = submitImages(userId, keys, "PRODUCT").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).at("/data/analysisId").asString();
    }

    private List<String> imageKeys(int count) {
        return IntStream.range(0, count).mapToObj(index -> "uploads/analysis/concurrency-" + index + ".png").toList();
    }

    private void dispatchConcurrently() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> {
                start.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
                dispatchDueAnalyses();
                return null;
            });
            Future<?> second = executor.submit(() -> {
                start.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
                dispatchDueAnalyses();
                return null;
            });
            first.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
            second.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private void callbackConcurrently(String aiAnalysisId) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> callbackAfter(start, aiAnalysisId));
            Future<?> second = executor.submit(() -> callbackAfter(start, aiAnalysisId));
            first.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
            second.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private Void callbackAfter(CyclicBarrier start, String aiAnalysisId) throws Exception {
        start.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        completeAnalysis(aiAnalysisId, "PRODUCT").andExpect(status().isOk());
        return null;
    }

    private void finish(AiImageAnalysisSubmitRequest request) throws Exception {
        unfinished.decrementAndGet();
        completeAnalysis(aiId(request), "PRODUCT").andExpect(status().isOk());
    }

    private String aiId(AiImageAnalysisSubmitRequest request) { return "ai-" + request.requestId(); }

    private void assertCompleted(Long userId, String analysisId, int count) throws Exception {
        String body = queryAnalysis(userId, analysisId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.results.length()").value(count))
                .andExpect(jsonPath("$.data.results[?(@.status == 'COMPLETED')]").value(hasSize(count)))
                .andReturn().getResponse().getContentAsString();
        JsonNode results = mapper.readTree(body).at("/data/results");
        for (int index = 0; index < count; index++) {
            assertThat(results.get(index).path("items").size()).isEqualTo(1);
            assertThat(results.get(index).at("/items/0/quantity").asInt()).isEqualTo(2);
        }
        assertSummary(userId, count, count);
    }

    private void assertSummary(Long userId, int total, int completed) throws Exception {
        listAnalyses(userId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analyses[0].totalImageCount").value(total))
                .andExpect(jsonPath("$.data.analyses[0].completedImageCount").value(completed))
                .andExpect(jsonPath("$.data.analyses[0].failedImageCount").value(0))
                .andExpect(jsonPath("$.data.analyses[0].images.length()").value(total));
    }
}
