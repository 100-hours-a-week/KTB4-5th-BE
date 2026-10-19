package com.dameokja.backend.push.application;

import com.dameokja.backend.global.exception.CustomException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerSlotsTest {
    @Test
    void rejectsInvalidLimitsAndOccupancyWithoutChangingTheCount() {
        assertThatThrownBy(() -> new WorkerSlots(0)).isInstanceOf(CustomException.class);
        WorkerSlots slots = new WorkerSlots(20);
        slots.occupy(17);
        assertThatThrownBy(() -> slots.occupy(4)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> slots.occupy(-1)).isInstanceOf(CustomException.class);
        slots.occupy(0);
        assertThat(slots.freeSlotCount()).isEqualTo(3);
    }

    @Test
    void rejectsExtraReleaseWithoutMakingTheCountNegative() {
        WorkerSlots slots = new WorkerSlots(2);
        slots.occupy(1);
        slots.release();
        assertThatThrownBy(slots::release).isInstanceOf(CustomException.class);
        assertThat(slots.freeSlotCount()).isEqualTo(2);
    }

    @Test
    void concurrentOccupancyCannotExceedCapacity() throws Exception {
        WorkerSlots slots = new WorkerSlots(20);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> calls = new ArrayList<>();
            for (int index = 0; index < 40; index++) {
                calls.add(executor.submit(() -> occupyAfterStart(slots, start)));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> call : calls) {
                if (call.get(5, TimeUnit.SECONDS)) accepted++;
            }
            assertThat(accepted).isEqualTo(20);
            assertThat(slots.freeSlotCount()).isZero();
        }
    }

    private boolean occupyAfterStart(WorkerSlots slots, CountDownLatch start) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            slots.occupy(1);
            return true;
        } catch (CustomException exception) {
            return false;
        }
    }
}
