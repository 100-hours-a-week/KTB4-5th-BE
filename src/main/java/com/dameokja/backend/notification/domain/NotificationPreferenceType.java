package com.dameokja.backend.notification.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 만료·임박 알림은 종류가 다르지만 수신 여부는 EXPIRATION 하나로 관리한다.
// 로컬 가입은 RECIPE를 꺼 두고, 소셜 가입은 요청한 수신 설정을 모든 유형에 적용한다.
@Getter
@AllArgsConstructor
public enum NotificationPreferenceType {
    EXPIRATION(true),
    RECIPE(false);

    private final boolean enabledOnSignup;
}
