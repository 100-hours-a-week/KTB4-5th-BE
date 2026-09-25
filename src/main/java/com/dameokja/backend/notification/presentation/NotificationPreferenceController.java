package com.dameokja.backend.notification.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.notification.application.NotificationPreferenceService;
import com.dameokja.backend.notification.presentation.response.NotificationPreferencesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications/settings")
public class NotificationPreferenceController implements NotificationPreferenceApi {
    private static final String PREFERENCES_CODE = "NOTI-200-001";
    private static final String PREFERENCES_MESSAGE = "알림 설정 조회 성공";
    private final NotificationPreferenceService notificationPreferenceService;

    @Override
    @GetMapping
    public ResponseEntity<SuccessResponse<NotificationPreferencesResponse>> getPreferences(@CurrentUserId Long userId) {
        NotificationPreferencesResponse response = NotificationPreferencesResponse.from(
                notificationPreferenceService.getPreferences(userId));
        return ResponseEntity.ok(SuccessResponse.of(PREFERENCES_CODE, PREFERENCES_MESSAGE, response));
    }
}
