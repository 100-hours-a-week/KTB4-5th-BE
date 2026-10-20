package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.ingredient.application.list.IngredientListService;
import com.dameokja.backend.notification.application.NotificationPreferenceService;
import com.dameokja.backend.notification.application.NotificationPreferenceView;
import com.dameokja.backend.user.application.profile.UserProfile;
import com.dameokja.backend.user.application.profile.UserProfileService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MypageService {
    private final RefrigeratorService refrigerators;
    private final UserProfileService profiles;
    private final IngredientListService ingredients;
    private final MonthlyExpirationService expirations;
    private final NotificationPreferenceService preferences;

    /**
     * 냉장고 접근을 검증하고 마이페이지의 프로필·통계·설정을 같은 읽기 트랜잭션으로 조회합니다.
     * @param userId 인증된 회원 ID
     * @param refrigeratorId 현재 선택한 냉장고 ID
     * @return 월별 만료 누적과 유형별 알림 설정을 포함한 화면 조회 결과
     */
    public MypageView getOverview(Long userId, Long refrigeratorId) {
        MemberListView members = refrigerators.getMembers(userId, refrigeratorId);
        UserProfile profile = profiles.getProfile(userId);
        long ingredientsNum = ingredients.countStored(refrigeratorId);
        int expiredIngredientsNum = expirations.getCount(userId, refrigeratorId);
        List<NotificationPreferenceView> notificationPreferences = preferences.getPreferences(userId);
        return new MypageView(profile, refrigeratorId, members.refrigeratorName(), ingredientsNum,
                expiredIngredientsNum, members.memberNum(), notificationPreferences);
    }
}
