package com.dameokja.backend.push.domain;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.push.exception.PushExceptionCode;
import java.util.List;

public record SelectedJobIds(List<Long> values) {
    public SelectedJobIds {
        // 원본 목록이 바뀌어도 lease 기록 개수의 검증 기준은 유지한다.
        values = List.copyOf(values);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public void verifyLeaseRecorded(int updatedCount) {
        if (updatedCount != values.size()) {
            throw new CustomException(PushExceptionCode.LEASE_UPDATE_COUNT_MISMATCH);
        }
    }
}
