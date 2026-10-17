package com.dameokja.backend.refrigerator.application;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExpirationAccountingSchedulerTest {
    @Mock MonthlyExpirationService service;

    @Test
    void continuesOtherRefrigeratorsAndPagesAfterOneFails() {
        when(service.findPendingIds(0L, 100)).thenReturn(LongStream.rangeClosed(1, 100).boxed().toList());
        when(service.findPendingIds(100L, 100)).thenReturn(List.of(101L));
        doThrow(new IllegalStateException("retry next run")).when(service).synchronize(1L);

        new ExpirationAccountingScheduler(service).accountExpirations();

        verify(service).synchronize(2L);
        verify(service).synchronize(100L);
        verify(service).synchronize(101L);
    }
}
