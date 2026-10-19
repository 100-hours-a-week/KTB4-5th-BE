package com.dameokja.backend.analysis.domain;

import java.util.Arrays;
import java.util.Collection;

public enum RecognitionStatus {
    UNRECOGNIZED, NEEDS_REVIEW, AI_ESTIMATED, RECOGNIZED;

    public static RecognitionStatus aggregate(Collection<RecognitionStatus> statuses) {
        // 선언 순서는 이미지 대표 상태를 결정하는 낮은 인식 상태부터의 우선순위다.
        return Arrays.stream(values()).filter(statuses::contains).findFirst().orElse(UNRECOGNIZED);
    }
}
