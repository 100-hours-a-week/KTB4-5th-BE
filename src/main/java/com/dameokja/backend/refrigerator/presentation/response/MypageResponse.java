package com.dameokja.backend.refrigerator.presentation.response;

import com.dameokja.backend.notification.presentation.response.NotificationPreferenceResponse;
import com.dameokja.backend.refrigerator.application.MypageView;
import java.util.List;

public record MypageResponse(String userId, String nickname, String email, String profileImageUrl,
        String refrigeratorId, String refrigeratorName, long ingredientsNum, int expiredIngredientsNum,
        int memberNum, List<NotificationPreferenceResponse> notificationPreferences) {
    public static MypageResponse from(MypageView view, String imageUrl) {
        return new MypageResponse(view.profile().id().toString(), view.profile().nickname(),
                view.profile().email(), imageUrl, view.refrigeratorId().toString(), view.refrigeratorName(),
                view.ingredientsNum(), view.expiredIngredientsNum(), view.memberNum(),
                view.notificationPreferences().stream().map(NotificationPreferenceResponse::from).toList());
    }
}
