package com.dameokja.backend.notification.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 만료·임박 알림은 종류가 다르지만 수신 여부는 EXPIRATION 하나로 관리한다.
// v1은 레시피 추천 푸시를 제공하지 않으므로 RECIPE는 가입 시 꺼 둔 채로 행만 만든다.
@Getter
@AllArgsConstructor
public enum NotificationPreferenceType {
    EXPIRATION(true),
    RECIPE(false);

    private final boolean enabledOnSignup;
}
