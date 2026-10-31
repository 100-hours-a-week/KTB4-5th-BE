package com.dameokja.backend.analysis.application.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisFeedbackSubmittedEvent;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaPublisher;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class AnalysisFeedbackOutboxWorkerTest {
    private final AnalysisFeedbackOutbox analysisFeedbackOutbox = mock(AnalysisFeedbackOutbox.class);
    private final AnalysisKafkaPublisher analysisKafkaPublisher = mock(AnalysisKafkaPublisher.class);
    private final LeasedFeedback leasedFeedback = new LeasedFeedback(1L, "token");

    @Test
    void sendsTheStoredEventAndFinishesOnlyAfterPublicationSucceeds() {
        storedFeedback();
        when(analysisKafkaPublisher.publishFeedback(any(AnalysisFeedbackSubmittedEvent.class))).thenReturn(CompletableFuture.completedFuture(null));
        sender().sendDue();
        org.mockito.InOrder publicationOrder = inOrder(analysisKafkaPublisher, analysisFeedbackOutbox);
        publicationOrder.verify(analysisKafkaPublisher).publishFeedback(argThat((AnalysisFeedbackSubmittedEvent analysisFeedbackSubmittedEvent) ->
                analysisFeedbackSubmittedEvent.messageId().equals("stored-message") && analysisFeedbackSubmittedEvent.payload().requestId().equals("original-request")
                        && analysisFeedbackSubmittedEvent.payload().userId().equals(7L) && analysisFeedbackSubmittedEvent.payload().analysisId().equals("ai-1")));
        publicationOrder.verify(analysisFeedbackOutbox).finish(leasedFeedback);
        verify(analysisFeedbackOutbox, never()).retryLater(any(), any());
    }

    @Test
    void keepsTheFeedbackLeasedWhilePublicationIsStillPending() throws Exception {
        storedFeedback();
        CompletableFuture<Void> publication = new CompletableFuture<>();
        CountDownLatch publicationStarted = new CountDownLatch(1);
        when(analysisKafkaPublisher.publishFeedback(any(AnalysisFeedbackSubmittedEvent.class))).thenAnswer(invocation -> {
            publicationStarted.countDown();
            return publication;
        });
        try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            Future<?> delivery = executorService.submit(() -> sender().sendDue());
            try {
                assertThat(publicationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                verify(analysisFeedbackOutbox, never()).finish(any());
            } finally {
                publication.complete(null);
            }
            delivery.get(5, TimeUnit.SECONDS);
        }
        verify(analysisFeedbackOutbox).finish(leasedFeedback);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYNC", "ASYNC", "TIMEOUT"})
    void reschedulesPublicationFailuresWithoutMarkingTheFeedbackDone(String failureMode) {
        storedFeedback();
        if (failureMode.equals("SYNC")) {
            when(analysisKafkaPublisher.publishFeedback(any(AnalysisFeedbackSubmittedEvent.class))).thenThrow(new IllegalStateException("broker unavailable"));
        } else {
            CompletableFuture<Void> publication = failureMode.equals("TIMEOUT")
                    ? CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("delivery timeout"))
                    : CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"));
            when(analysisKafkaPublisher.publishFeedback(any(AnalysisFeedbackSubmittedEvent.class))).thenReturn(publication);
        }
        sender().sendDue();
        verify(analysisFeedbackOutbox).retryLater(leasedFeedback, AnalysisFeedbackErrorCode.PUBLISH_FAILED);
        verify(analysisFeedbackOutbox, never()).finish(any());
    }

    @Test
    void stopsMalformedStoredEventsWithoutPublishingThem() {
        AnalysisFeedback analysisFeedback = storedFeedback();
        when(analysisFeedback.getFeedbackBody()).thenReturn("not-json");
        sender().sendDue();
        verify(analysisFeedbackOutbox).fail(leasedFeedback, AnalysisFeedbackErrorCode.INVALID_EVENT);
        verifyNoInteractions(analysisKafkaPublisher);
    }

    @Test
    void preservesTheLeaseForRecoveryWhenPublicationIsInterrupted() {
        storedFeedback();
        when(analysisKafkaPublisher.publishFeedback(any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return new CompletableFuture<Void>();
        });
        try {
            sender().sendDue();
            verify(analysisFeedbackOutbox, never()).finish(any());
            verify(analysisFeedbackOutbox, never()).retryLater(any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishesBothFeedbacksBeforeAckAndFinishesSuccessWhileAnotherFeedbackStillWaits(boolean retryStorageFails) throws Exception {
        LeasedFeedback secondLease = storedSecondFeedback();
        if (retryStorageFails) {
            doThrow(new IllegalStateException("database unavailable")).when(analysisFeedbackOutbox).retryLater(leasedFeedback, AnalysisFeedbackErrorCode.PUBLISH_FAILED);
        }
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch finished = new CountDownLatch(1);
        CompletableFuture<Void> firstAck = new CompletableFuture<>();
        CompletableFuture<Void> secondAck = new CompletableFuture<>();
        configurePublications(started, firstAck, secondAck);
        doAnswer(invocation -> { finished.countDown(); return null; }).when(analysisFeedbackOutbox).finish(secondLease);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> batch = executor.submit(() -> sender().sendDue());
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                secondAck.complete(null);
                assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
                verify(analysisFeedbackOutbox, never()).finish(leasedFeedback);
                assertThat(batch.isDone()).isFalse();
                firstAck.completeExceptionally(new org.apache.kafka.common.errors.TimeoutException("delivery timeout"));
                batch.get(5, TimeUnit.SECONDS);
            } finally {
                firstAck.complete(null);
                secondAck.complete(null);
            }
        }
        verify(analysisFeedbackOutbox).retryLater(leasedFeedback, AnalysisFeedbackErrorCode.PUBLISH_FAILED);
        verify(analysisFeedbackOutbox).finish(secondLease);
    }

    private void configurePublications(CountDownLatch started, CompletableFuture<Void> firstAck, CompletableFuture<Void> secondAck) {
        when(analysisKafkaPublisher.publishFeedback(any())).thenAnswer(invocation -> {
            AnalysisFeedbackSubmittedEvent event = invocation.getArgument(0);
            started.countDown();
            return event.payload().analysisId().equals("ai-1") ? firstAck : secondAck;
        });
    }

    private LeasedFeedback storedSecondFeedback() {
        String secondBody = storedFeedback().getFeedbackBody().replace("ai-1", "ai-2");
        LeasedFeedback secondLease = new LeasedFeedback(2L, "second-token");
        AnalysisFeedback second = mock(AnalysisFeedback.class);
        when(second.getFeedbackBody()).thenReturn(secondBody);
        when(analysisFeedbackOutbox.findLeased(secondLease)).thenReturn(Optional.of(second));
        when(analysisFeedbackOutbox.leaseDue()).thenReturn(List.of(leasedFeedback, secondLease));
        return secondLease;
    }

    private AnalysisFeedbackOutboxWorker sender() {
        return new AnalysisFeedbackOutboxWorker(analysisFeedbackOutbox, analysisKafkaPublisher, JsonMapper.builder().build());
    }

    private AnalysisFeedback storedFeedback() {
        when(analysisFeedbackOutbox.leaseDue()).thenReturn(List.of(leasedFeedback));
        AnalysisFeedback analysisFeedback = mock(AnalysisFeedback.class);
        when(analysisFeedback.getFeedbackBody()).thenReturn("""
                {"messageId":"stored-message","payload":{"eventType":"AnalysisFeedbackSubmitted","occurredAt":"2026-10-31T03:00:00Z",
                 "requestId":"original-request","userId":7,"analysisId":"ai-1","refrigeratorId":17,"consentForModelImprovement":true,
                 "items":[{"itemId":"item-1","action":"CONFIRMED"}]}}
                """);
        when(analysisFeedbackOutbox.findLeased(leasedFeedback)).thenReturn(Optional.of(analysisFeedback));
        return analysisFeedback;
    }
}
