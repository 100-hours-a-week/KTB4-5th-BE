package com.dameokja.backend.push.domain;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.push.exception.PushExceptionCode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectedJobIdsTest {
    @Test
    void keepsTheSelectedBatchEvenWhenTheOriginalListChanges() {
        List<Long> source = new ArrayList<>(List.of(101L, 102L));
        SelectedJobIds ids = new SelectedJobIds(source);
        source.clear();
        assertThat(ids.values()).containsExactly(101L, 102L);
        ids.verifyLeaseRecorded(2);
    }

    @Test
    void rejectsAnIncompleteLeaseWrite() {
        SelectedJobIds ids = new SelectedJobIds(List.of(101L, 102L));
        assertThatThrownBy(() -> ids.verifyLeaseRecorded(1)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode").isEqualTo(PushExceptionCode.LEASE_UPDATE_COUNT_MISMATCH);
    }

    @Test
    void acceptsAnEmptySelection() {
        SelectedJobIds ids = new SelectedJobIds(List.of());
        assertThat(ids.isEmpty()).isTrue();
        ids.verifyLeaseRecorded(0);
    }
}
