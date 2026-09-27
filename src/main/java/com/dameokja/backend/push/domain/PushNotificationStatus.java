package com.dameokja.backend.push.domain;

// 단일 인스턴스에서 하나의 배치 작업이 처리하므로, 여러 작업자가 같은 행을 나눠 가질 때 쓰는 선점 상태(PROCESSING)는 두지 않는다.
public enum PushNotificationStatus {
    PENDING,
    RETRY,
    ACCEPTED,
    FAILED,
    CANCELLED
}
