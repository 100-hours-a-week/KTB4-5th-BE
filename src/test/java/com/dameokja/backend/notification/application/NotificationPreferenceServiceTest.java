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
import static org.mockito.Mockito.when;

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

    @Test
    void returnsPreferencesOrderedByType() {
        User user = new User("User1", "profiles/default.png");
        when(notificationPreferenceRepository.findAllByUserId(1L)).thenReturn(List.of(
                NotificationPreference.onSignup(user, NotificationPreferenceType.RECIPE),
                NotificationPreference.onSignup(user, NotificationPreferenceType.EXPIRATION)));

        List<NotificationPreferenceView> preferences = notificationPreferenceService.getPreferences(1L);

        assertThat(preferences).containsExactly(
                new NotificationPreferenceView(NotificationPreferenceType.EXPIRATION, true),
                new NotificationPreferenceView(NotificationPreferenceType.RECIPE, false));
    }
}
