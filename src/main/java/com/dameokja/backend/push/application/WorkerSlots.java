package com.dameokja.backend.push.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.push.exception.PushExceptionCode;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class WorkerSlots {
    private final int maxSlotCount;
    private final AtomicInteger activeSlotCount = new AtomicInteger();

    public WorkerSlots(@Value("${push.dispatch.concurrency}") int maxSlotCount) {
        if (maxSlotCount <= 0) {
            throw new CustomException(PushExceptionCode.WORKER_SLOT_LIMIT_INVALID);
        }
        this.maxSlotCount = maxSlotCount;
    }

    public int freeSlotCount() {
        return maxSlotCount - activeSlotCount.get();
    }

    public void occupy(int jobCount) {
        if (jobCount < 0) {
            throw new CustomException(PushExceptionCode.WORKER_SLOT_OCCUPANCY_INVALID);
        }
        // 다른 스레드가 개수를 변경하면 최신 개수로 한도를 다시 검사한다.
        activeSlotCount.updateAndGet(currentCount -> {
            if (jobCount > maxSlotCount - currentCount) {
                throw new CustomException(PushExceptionCode.WORKER_SLOT_OCCUPANCY_INVALID);
            }
            return currentCount + jobCount;
        });
    }

    public void release() {
        activeSlotCount.updateAndGet(currentCount -> {
            if (currentCount <= 0) {
                throw new CustomException(PushExceptionCode.WORKER_SLOT_RELEASE_INVALID);
            }
            return currentCount - 1;
        });
    }
}
