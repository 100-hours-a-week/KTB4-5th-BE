package com.dameokja.backend.user.application;

import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.user.domain.SocialAccount;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.infrastructure.SocialAccountRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserProfileService {
    private final UserAccessService userAccessService;
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final SocialAccountRepository socialAccountRepository;

    /**
     * 활성 회원의 프로필과 현재 선택한 냉장고 정보를 조회합니다.
     * @param userId 인증된 회원의 식별자
     * @return 저장된 프로필과 카카오 이메일, 활성 냉장고 식별자 목록과 만든 요리 수
     */
    public UserProfile getProfile(Long userId) {
        User user = userAccessService.getActive(userId);
        String email = socialAccountRepository.findByUserIdAndProvider(userId, OAuthProvider.KAKAO)
                .map(SocialAccount::getProviderEmail).orElse(null);
        List<Long> refrigeratorIds = refrigeratorAccessService.findActiveRefrigeratorIds(userId);
        return new UserProfile(user.getId(), user.getNickname(), email, user.getProfileImageKey(),
                refrigeratorIds, user.getCookingCount());
    }
}
