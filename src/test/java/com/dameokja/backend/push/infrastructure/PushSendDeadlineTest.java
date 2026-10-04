package com.dameokja.backend.push.infrastructure;

import java.net.SocketTimeoutException;
import java.time.Duration;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class PushSendDeadlineTest {
    private static final Duration COMPLETION_DEADLINE = Duration.ofSeconds(1);
    private static final Duration EXPIRATION_DEADLINE = Duration.ofMillis(100);
    private static final Duration OBSERVATION_MARGIN = Duration.ofMillis(500);
    private static final Duration CANCELLATION_WAIT = Duration.ofSeconds(5);

    @Test
    void doesNotCancelCompletedRequestAfterDeadline() throws Exception {
        HttpPost request = mock(HttpPost.class);
        try (PushSendDeadline deadline = new PushSendDeadline(request, COMPLETION_DEADLINE)) {
            PushSendDeadline.check();
            assertThat(deadline.finishWithinDeadline()).isTrue();
            // 자동 close()가 취소 방지 실패를 가리지 않도록 완료 처리 직후부터 관찰한다.
            verify(request, after(COMPLETION_DEADLINE.plus(OBSERVATION_MARGIN).toMillis()).never()).cancel();
        }
    }

    @Test
    void cancelsExpiredRequestAndRejectsFurtherWork() {
        HttpPost request = mock(HttpPost.class);
        try (PushSendDeadline deadline = new PushSendDeadline(request, EXPIRATION_DEADLINE)) {
            verify(request, timeout(CANCELLATION_WAIT.toMillis())).cancel();
            assertThatThrownBy(PushSendDeadline::check).isInstanceOf(SocketTimeoutException.class);
            assertThat(deadline.finishWithinDeadline()).isFalse();
        }
    }

    @Test
    void doesNotApplyExpiredDeadlineAfterScopeEnds() {
        HttpPost request = mock(HttpPost.class);
        try (PushSendDeadline ignored = new PushSendDeadline(request, EXPIRATION_DEADLINE)) {
            verify(request, timeout(CANCELLATION_WAIT.toMillis())).cancel();
        }
        assertThatCode(PushSendDeadline::check).doesNotThrowAnyException();
    }
}
