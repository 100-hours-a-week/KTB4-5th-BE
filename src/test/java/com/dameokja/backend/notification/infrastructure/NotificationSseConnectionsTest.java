package com.dameokja.backend.notification.infrastructure;

import static com.dameokja.backend.analysis.domain.AnalysisStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.dameokja.backend.refrigerator.domain.MemberKicked;
import com.dameokja.backend.notification.domain.AnalysisFinished;
import com.dameokja.backend.notification.domain.AnalysisImageFinished;
import com.dameokja.backend.recipe.domain.RecommendationGenerationFinished;
import com.dameokja.backend.recipe.domain.RecommendationState.GenerationStatus;
import com.dameokja.backend.recipe.domain.RecommendationState.GenerationTrigger;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseConnectionsTest {
    private final SseEmitter first = mock(SseEmitter.class);
    private final SseEmitter second = mock(SseEmitter.class);
    private final SseEmitter otherUser = mock(SseEmitter.class);
    private NotificationSseConnections connections;

    @BeforeEach
    void setup() {
        ArrayDeque<SseEmitter> emitters = new ArrayDeque<>();
        emitters.add(first);
        emitters.add(second);
        emitters.add(otherUser);
        connections = new NotificationSseConnections(emitters::remove, mock(NotificationConnectionPresence.class));
    }

    @Test
    void recommendationReusesEveryOwnStreamAndExposesOnlyTriggerAndStatus() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 18L);
        connections.connect(2L, 17L);
        connections.notifyRecommendationFinished(new RecommendationGenerationFinished(1L, 17L, GenerationTrigger.SCHEDULED, GenerationStatus.COMPLETED));
        assertThat(recommendationEvents(first)).singleElement().satisfies(event ->
                assertThat(event.build()).anyMatch(part -> part.getData().equals(Map.of("generationTrigger", "SCHEDULED", "generationStatus", "COMPLETED"))));
        assertThat(recommendationEvents(second)).hasSize(1);
        assertThat(recommendationEvents(otherUser)).isEmpty();
    }

    @Test
    void recommendationSendFailureRemovesOnlyTheFailedConnection() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 18L);
        doThrow(new IOException("closed")).when(first).send(any(SseEmitter.SseEventBuilder.class));
        connections.notifyRecommendationFinished(new RecommendationGenerationFinished(1L, 17L, GenerationTrigger.USER, GenerationStatus.FAILED));
        connections.notifyRecommendationFinished(new RecommendationGenerationFinished(1L, 17L, GenerationTrigger.USER, GenerationStatus.COMPLETED));
        assertThat(recommendationEvents(first)).hasSize(1);
        verify(first).completeWithError(any(IOException.class));
        assertThat(recommendationEvents(second)).hasSize(2);
    }

    private List<SseEmitter.SseEventBuilder> recommendationEvents(SseEmitter emitter) throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, atLeastOnce()).send(events.capture());
        return events.getAllValues().stream()
                .filter(event -> event.build().stream().anyMatch(part -> part.getData().toString().contains("event:recommendation"))).toList();
    }

    @Test
    void kickedSendsEmptyJsonToEveryMatchingConnectionOnly() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 17L);
        connections.connect(2L, 17L);

        connections.notifyKicked(new MemberKicked(1L, 17L));

        ArgumentCaptor<SseEmitter.SseEventBuilder> events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(first, times(2)).send(events.capture());
        assertThat(events.getValue().build()).anyMatch(part -> part.getData().equals("{}"));
        assertThat(events.getValue().build()).anyMatch(part -> part.getData().toString().contains("event:kicked"));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherUser).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void kickedIgnoresOtherFridgesAndCleansFailedConnections() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 18L);
        doThrow(new IOException("closed")).when(first).send(any(SseEmitter.SseEventBuilder.class));

        connections.notifyKicked(new MemberKicked(1L, 17L));
        connections.notifyKicked(new MemberKicked(3L, 17L));
        connections.heartbeat();

        verify(first, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(first).completeWithError(any(IOException.class));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void sendsImageFinishedPayloadToAllOwnConnectionsWithoutExpiry() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 18L);
        connections.connect(2L, 17L);

        connections.notifyAnalysisImageFinished(new AnalysisImageFinished(1L, new AnalysisImageFinished.Payload("analysis-id", "image-key", FAILED, 3, 0, 1, null)));

        ArgumentCaptor<SseEmitter.SseEventBuilder> events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(first, times(2)).send(events.capture());
        assertThat(events.getValue().build()).anyMatch(part -> part.getData().toString().contains("event:image-analysis.image-finished"));
        assertThat(events.getValue().build()).anyMatch(part -> part.getData().equals(
                new AnalysisImageFinished.Payload("analysis-id", "image-key", FAILED, 3, 0, 1, null)));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherUser).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void sendsAnalysisFinishedToEveryOwnConnectionRegardlessOfFridge() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 18L);
        connections.connect(2L, 17L);
        AnalysisFinished finished = AnalysisFinished.of(1L, "analysis-id", COMPLETED, 3, 3, 0, OffsetDateTime.parse("2026-10-22T19:00:00+09:00"));

        connections.notifyAnalysisFinished(finished);

        ArgumentCaptor<SseEmitter.SseEventBuilder> events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(first, times(2)).send(events.capture());
        assertThat(events.getValue().build()).anyMatch(part -> part.getData().toString().contains("event:image-analysis.finished"));
        assertThat(events.getValue().build()).anyMatch(part -> part.getData().equals(finished.payload()));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherUser).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void analysisFailureRemovesOnlyFailedConnectionAndMissingUserIsIgnored() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 18L);
        doThrow(new IOException("closed")).when(first).send(any(SseEmitter.SseEventBuilder.class));
        connections.notifyAnalysisFinished(AnalysisFinished.of(1L, "analysis-id", COMPLETED, 3, 3, 0, OffsetDateTime.parse("2026-10-22T19:00:00+09:00")));
        connections.notifyAnalysisFinished(AnalysisFinished.of(3L, "other-analysis", COMPLETED, 3, 3, 0, OffsetDateTime.parse("2026-10-22T19:00:00+09:00")));
        connections.heartbeat();

        verify(first, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(first).completeWithError(any(IOException.class));
        verify(second, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void sendsChangesToEveryConnectionOfOnlyTheRecipient() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 17L);
        connections.connect(2L, 17L);

        connections.notifyChanged(1L, 17L);

        verify(first, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherUser).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void sendsChangesOnlyToConnectionsForTheMatchingUserAndFridge() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 18L);
        connections.connect(2L, 17L);

        connections.notifyChanged(1L, 17L);

        verify(first, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(second).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherUser).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void reconnectForAnotherFridgeDoesNotReceiveThePreviousFridgesChanges() throws Exception {
        connections.connect(1L, 17L);
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(first).onCompletion(completion.capture());
        completion.getValue().run();
        connections.connect(1L, 18L);

        connections.notifyChanged(1L, 17L);
        verify(second).send(any(SseEmitter.SseEventBuilder.class));
        connections.notifyChanged(1L, 18L);

        verify(first).send(any(SseEmitter.SseEventBuilder.class));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void completionAndTimeoutRemoveOnlyTheirOwnConnection() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 17L);
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        verify(first).onCompletion(completion.capture());
        verify(second).onTimeout(timeout.capture());
        completion.getValue().run();

        connections.notifyChanged(1L, 17L);

        verify(first).send(any(SseEmitter.SseEventBuilder.class));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        timeout.getValue().run();
        connections.heartbeat();
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(second).complete();
    }

    @Test
    void failedConnectionDoesNotBlockOtherConnectionsAndIsRemoved() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 17L);
        doThrow(new IOException("disconnected")).when(first).send(any(SseEmitter.SseEventBuilder.class));

        connections.notifyChanged(1L, 17L);
        connections.heartbeat();

        verify(first, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(first).completeWithError(any(IOException.class));
        verify(second, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void errorCallbackRemovesConnectionAndReconnectReceivesReadyAndChanges() throws Exception {
        connections.connect(1L, 17L);
        ArgumentCaptor<Consumer<Throwable>> error = ArgumentCaptor.forClass(Consumer.class);
        verify(first).onError(error.capture());
        error.getValue().accept(new IOException("closed"));
        connections.connect(1L, 17L);

        connections.notifyChanged(1L, 17L);

        verify(first).send(any(SseEmitter.SseEventBuilder.class));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void initialSendFailureIsRemovedBeforeHeartbeat() throws Exception {
        doThrow(new IOException("closed")).when(first).send(any(SseEmitter.SseEventBuilder.class));
        connections.connect(1L, 17L);
        connections.heartbeat();
        verify(first).send(any(SseEmitter.SseEventBuilder.class));
        verify(first).completeWithError(any(IOException.class));
    }

    @Test
    void connectionImmediatelySendsReadyEventAndShutdownCompletesIt() throws Exception {
        assertThat(connections.connect(1L, 17L)).isSameAs(first);
        ArgumentCaptor<SseEmitter.SseEventBuilder> event = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(first).send(event.capture());
        assertThat(event.getValue().build()).anyMatch(part -> part.getData().toString().contains("connected"));

        connections.shutdown();

        verify(first).complete();
    }
}
