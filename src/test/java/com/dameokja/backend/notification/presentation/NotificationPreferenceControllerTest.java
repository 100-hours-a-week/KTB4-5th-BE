package com.dameokja.backend.notification.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.notification.application.NotificationPreferenceService;
import com.dameokja.backend.notification.application.NotificationPreferenceView;
import com.dameokja.backend.notification.domain.NotificationPreferenceType;
import com.dameokja.backend.notification.presentation.response.NotificationPreferenceResponse;
import com.dameokja.backend.notification.presentation.response.NotificationPreferencesResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceControllerTest {
    @Mock
    private NotificationPreferenceService notificationPreferenceService;

    @Test
    void returnsPreferencesOfCurrentUser() {
        when(notificationPreferenceService.getPreferences(1L)).thenReturn(List.of(
                new NotificationPreferenceView(NotificationPreferenceType.EXPIRATION, true),
                new NotificationPreferenceView(NotificationPreferenceType.RECIPE, false)));
        NotificationPreferenceController controller = new NotificationPreferenceController(notificationPreferenceService);

        ResponseEntity<SuccessResponse<NotificationPreferencesResponse>> response = controller.getPreferences(1L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().code()).isEqualTo("NOTI-200-001");
        assertThat(response.getBody().data().notificationPreferences()).containsExactly(
                new NotificationPreferenceResponse(NotificationPreferenceType.EXPIRATION, true),
                new NotificationPreferenceResponse(NotificationPreferenceType.RECIPE, false));
    }
}
