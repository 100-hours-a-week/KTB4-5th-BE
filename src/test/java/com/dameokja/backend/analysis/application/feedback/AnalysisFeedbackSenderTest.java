package com.dameokja.backend.analysis.application.feedback;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

class AnalysisFeedbackSenderTest {
    private final AnalysisFeedbackQueue queue = mock(AnalysisFeedbackQueue.class);
    private final AiAnalysisClient ai = mock(AiAnalysisClient.class);
    private final AnalysisFeedbackSender sender = new AnalysisFeedbackSender(queue, ai, JsonMapper.builder().build());
    private final LeasedFeedback leased = new LeasedFeedback(1L, "token");

    @Test
    void sendsStoredConfirmedValuesAndFinishesAfterSuccess() {
        storedFeedback();
        when(ai.feedback(eq("ai-1"), any())).thenReturn(new AiAnalysisFeedbackResponse("feedback-1", "RECORDED"));
        sender.sendDue();
        org.mockito.InOrder order = inOrder(ai, queue);
        order.verify(ai).feedback(eq("ai-1"), argThat(request -> request.refrigeratorId().equals(17L) && request.items().size() == 2
                && request.items().getFirst().itemId().equals("item-1") && request.items().getFirst().confirmed().name().equals("우유")
                && request.items().getFirst().confirmed().quantity() == 2 && request.items().getLast().itemId().equals("item-2")));
        order.verify(queue).finish(leased);
        verify(queue, never()).retryLater(any(), any());
        verify(queue, never()).fail(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIMEOUT", "UNAVAILABLE", "BUSY", "REJECTED"})
    void retriesTemporaryFailuresAndStopsPermanentRejections(String outcome) {
        storedFeedback();
        RuntimeException failure = switch (outcome) {
            case "TIMEOUT" -> new ResourceAccessException("timeout");
            case "UNAVAILABLE" -> new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
            case "BUSY" -> new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
            default -> new HttpClientErrorException(HttpStatus.NOT_FOUND);
        };
        when(ai.feedback(eq("ai-1"), any())).thenThrow(failure);
        sender.sendDue();
        if (outcome.equals("REJECTED")) {
            verify(queue).fail(leased, AnalysisFeedbackErrorCode.AI_REJECTED);
            verify(queue, never()).retryLater(any(), any());
        } else {
            verify(queue).retryLater(leased, outcome.equals("BUSY") ? AnalysisFeedbackErrorCode.AI_BUSY : AnalysisFeedbackErrorCode.AI_UNAVAILABLE);
            verify(queue, never()).fail(any(), any());
        }
        verify(queue, never()).finish(any());
    }

    @Test
    void doesNotLeaseWhenAiIsNotReady() {
        sender.sendDue();
        verify(queue).failExpired();
        verify(queue, never()).leaseDue();
        verify(ai, never()).feedback(any(), any());
    }

    private void storedFeedback() {
        when(ai.ready()).thenReturn(true);
        when(queue.leaseDue()).thenReturn(List.of(leased));
        AnalysisFeedback feedback = mock(AnalysisFeedback.class);
        when(feedback.getAiAnalysisId()).thenReturn("ai-1");
        when(feedback.getFeedbackBody()).thenReturn("""
                {"refrigeratorId":17,"consentForModelImprovement":true,"items":[
                 {"itemId":"item-1","action":"CORRECTED","confirmed":{"name":"우유","quantity":2}},
                 {"itemId":"item-2","action":"CORRECTED","confirmed":{"name":"계란","quantity":1}}]}
                """);
        when(queue.findLeased(leased)).thenReturn(Optional.of(feedback));
    }
}
