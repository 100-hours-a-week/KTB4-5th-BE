package com.dameokja.backend.push.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.push.domain.LeaseDuration;
import com.dameokja.backend.push.exception.PushExceptionCode;
import com.dameokja.backend.push.infrastructure.PushNotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PushLeaseServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 8, 0);
    private static final LeaseDuration LEASE_DURATION = new LeaseDuration(Duration.ofMinutes(1));
    private static final LocalDateTime END_AT = NOW.plusMinutes(1);
    private static final List<Long> JOB_IDS = List.of(101L, 105L);

    private final PushNotificationRepository repository = mock(PushNotificationRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T23:00:00Z"), ZoneOffset.UTC);
    private final PushLeaseService service = new PushLeaseService(repository, clock);

    @Test
    void leasesOnlySelectedIdsAndReturnsTheirToken() {
        when(repository.findLeaseableJobIdsWithSkipLock(NOW, 3)).thenReturn(JOB_IDS);
        when(repository.leasePushJobs(eq(JOB_IDS), anyString(), eq(NOW), eq(END_AT))).thenReturn(2);

        List<LeasedJob> jobs = service.lease(3, LEASE_DURATION);

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(repository);
        order.verify(repository).findLeaseableJobIdsWithSkipLock(NOW, 3);
        order.verify(repository).leasePushJobs(eq(JOB_IDS), token.capture(), eq(NOW), eq(END_AT));
        assertThat(token.getValue()).isNotBlank();
        assertThat(jobs).containsExactly(new LeasedJob(101L, token.getValue()), new LeasedJob(105L, token.getValue()));
    }

    @Test
    void generatesANewTokenForEachLeaseBatch() {
        when(repository.findLeaseableJobIdsWithSkipLock(NOW, 2)).thenReturn(JOB_IDS);
        when(repository.leasePushJobs(eq(JOB_IDS), anyString(), eq(NOW), eq(END_AT))).thenReturn(2);

        String firstToken = service.lease(2, LEASE_DURATION).getFirst().leaseToken();
        String secondToken = service.lease(2, LEASE_DURATION).getFirst().leaseToken();

        assertThat(firstToken).isNotEqualTo(secondToken);
    }

    @Test
    void doesNotAccessDatabaseWithoutFreeSlots() {
        assertThat(service.lease(0, LEASE_DURATION)).isEmpty();
        assertThat(service.lease(-1, LEASE_DURATION)).isEmpty();

        verifyNoInteractions(repository);
    }

    @Test
    void doesNotUpdateWhenNoJobsAreAvailable() {
        when(repository.findLeaseableJobIdsWithSkipLock(NOW, 3)).thenReturn(List.of());

        assertThat(service.lease(3, LEASE_DURATION)).isEmpty();

        verify(repository, never()).leasePushJobs(anyList(), anyString(), any(), any());
    }

    @Test
    void rejectsAnIncompleteLeaseUpdate() {
        when(repository.findLeaseableJobIdsWithSkipLock(NOW, 2)).thenReturn(JOB_IDS);
        when(repository.leasePushJobs(eq(JOB_IDS), anyString(), eq(NOW), eq(END_AT))).thenReturn(1);

        assertThatThrownBy(() -> service.lease(2, LEASE_DURATION))
                .isInstanceOf(CustomException.class)
                .extracting("exceptionCode").isEqualTo(PushExceptionCode.LEASE_UPDATE_COUNT_MISMATCH);
    }

}
