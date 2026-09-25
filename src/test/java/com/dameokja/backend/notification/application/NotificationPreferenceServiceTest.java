package com.dameokja.backend.notification.application;

import com.dameokja.backend.notification.domain.NotificationPreference;
import com.dameokja.backend.notification.domain.NotificationPreferenceType;
import com.dameokja.backend.notification.infrastructure.NotificationPreferenceRepository;
import com.dameokja.backend.user.domain.User;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {
    @Mock
    private NotificationPreferenceRepository notificationPreferenceRepository;
    @InjectMocks
    private NotificationPreferenceService notificationPreferenceService;

    @Test
    void createsExpirationEnabledAndRecipeDisabledOnSignup() {
        User user = new User("User1", "profiles/default.png");

        notificationPreferenceService.createDefaults(user);

        ArgumentCaptor<NotificationPreference> savedCaptor =
                ArgumentCaptor.forClass(NotificationPreference.class);
        verify(notificationPreferenceRepository, times(2)).save(savedCaptor.capture());
        List<NotificationPreference> saved = savedCaptor.getAllValues();
        assertThat(saved).extracting(
                        NotificationPreference::getUser,
                        NotificationPreference::getType,
                        NotificationPreference::getIsEnabled)
                .containsExactlyInAnyOrder(
                        tuple(user, NotificationPreferenceType.EXPIRATION, true),
                        tuple(user, NotificationPreferenceType.RECIPE, false));
    }
}
