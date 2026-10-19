package com.dameokja.backend.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class RecognitionStatusTest {
    @ParameterizedTest
    @EnumSource(RecognitionStatus.class)
    void includesEveryDeclaredStatus(RecognitionStatus status) {
        assertThat(RecognitionStatus.aggregate(List.of(status))).isEqualTo(status);
    }

    @ParameterizedTest
    @CsvSource({"RECOGNIZED,UNRECOGNIZED,UNRECOGNIZED", "AI_ESTIMATED,NEEDS_REVIEW,NEEDS_REVIEW", "RECOGNIZED,AI_ESTIMATED,AI_ESTIMATED"})
    void selectsLowestStatusRegardlessOfItemOrder(RecognitionStatus first, RecognitionStatus second, RecognitionStatus expected) {
        assertThat(RecognitionStatus.aggregate(List.of(first, second))).isEqualTo(expected);
        assertThat(RecognitionStatus.aggregate(List.of(second, first))).isEqualTo(expected);
    }

    @Test
    void defaultsToUnrecognizedForEmptyItems() {
        assertThat(RecognitionStatus.aggregate(List.of())).isEqualTo(RecognitionStatus.UNRECOGNIZED);
    }
}
