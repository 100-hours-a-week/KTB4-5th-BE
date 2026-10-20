package com.dameokja.backend.refrigerator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.list.IngredientListService;
import com.dameokja.backend.notification.application.NotificationPreferenceService;
import com.dameokja.backend.notification.application.NotificationPreferenceView;
import com.dameokja.backend.notification.domain.NotificationPreferenceType;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.user.application.profile.UserProfile;
import com.dameokja.backend.user.application.profile.UserProfileService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MypageServiceTest {
    @Mock RefrigeratorService refrigerators;
    @Mock UserProfileService profiles;
    @Mock IngredientListService ingredients;
    @Mock MonthlyExpirationService expirations;
    @Mock NotificationPreferenceService preferences;
    @InjectMocks MypageService service;

    @Test
    void returnsMonthlyTotalSeparatelyFromRemainingStockWithOriginalPreferences() {
        UserProfile profile = new UserProfile(1L, "dave", null, "profiles/default.png", List.of(17L), 0);
        List<NotificationPreferenceView> settings = List.of(
                new NotificationPreferenceView(NotificationPreferenceType.EXPIRATION, true),
                new NotificationPreferenceView(NotificationPreferenceType.RECIPE, false));
        when(refrigerators.getMembers(1L, 17L)).thenReturn(new MemberListView("1", "우리집", 2, 150, List.of()));
        when(profiles.getProfile(1L)).thenReturn(profile);
        when(ingredients.countStored(17L)).thenReturn(0L);
        when(expirations.getCount(1L, 17L)).thenReturn(5);
        when(preferences.getPreferences(1L)).thenReturn(settings);

        MypageView result = service.getOverview(1L, 17L);

        assertThat(result.profile()).isEqualTo(profile);
        assertThat(result.refrigeratorId()).isEqualTo(17L);
        assertThat(result.refrigeratorName()).isEqualTo("우리집");
        assertThat(result.ingredientsNum()).isZero();
        assertThat(result.expiredIngredientsNum()).isEqualTo(5);
        assertThat(result.memberNum()).isEqualTo(2);
        assertThat(result.notificationPreferences()).isEqualTo(settings);
    }

    @Test
    void deniedRefrigeratorCannotReadProfileOrStatistics() {
        CustomException denied = new CustomException(RefrigeratorExceptionCode.ACCESS_DENIED);
        when(refrigerators.getMembers(1L, 17L)).thenThrow(denied);

        assertThatThrownBy(() -> service.getOverview(1L, 17L)).isSameAs(denied);

        verifyNoInteractions(profiles, ingredients, expirations, preferences);
    }
}
