package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.notification.application.preference.NotificationPreferenceView;
import com.dameokja.backend.user.application.profile.UserProfile;
import java.util.List;

public record MypageView(UserProfile profile, Long refrigeratorId, String refrigeratorName,
        long ingredientsNum, int expiredIngredientsNum, int memberNum,
        List<NotificationPreferenceView> notificationPreferences) {}
