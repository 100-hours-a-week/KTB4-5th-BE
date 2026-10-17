package com.dameokja.backend.user.application;

import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.image.application.ProfileImageService;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.user.domain.SocialAccount;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.ProfileImageChange;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.SocialAccountRepository;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserProfileService {
    private final UserAccessService userAccessService;
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository users;
    private final NicknamePolicy nicknamePolicy;
    private final ProfileImageService profileImages;
    private final TransactionTemplate transaction;
    private final String defaultImageKey;

    public UserProfileService(UserAccessService userAccessService, RefrigeratorAccessService refrigeratorAccessService,
            SocialAccountRepository socialAccountRepository, UserRepository users, NicknamePolicy nicknamePolicy,
            ProfileImageService profileImages, PlatformTransactionManager transactions,
            @Value("${app.user.default-profile-image-key:profiles/default.png}") String defaultImageKey) {
        this.userAccessService = userAccessService;
        this.refrigeratorAccessService = refrigeratorAccessService;
        this.socialAccountRepository = socialAccountRepository;
        this.users = users;
        this.nicknamePolicy = nicknamePolicy;
        this.profileImages = profileImages;
        this.transaction = new TransactionTemplate(transactions);
        this.defaultImageKey = defaultImageKey;
    }

    /**
     * 활성 회원의 프로필과 현재 선택한 냉장고 정보를 조회합니다.
     * @param userId 인증된 회원의 식별자
     * @return 저장된 프로필과 카카오 이메일, 활성 냉장고 식별자 목록과 만든 요리 수
     */
    @Transactional(readOnly = true)
    public UserProfile getProfile(Long userId) {
        User user = userAccessService.getActive(userId);
        String email = socialAccountRepository.findByUserIdAndProvider(userId, OAuthProvider.KAKAO)
                .map(SocialAccount::getProviderEmail).orElse(null);
        List<Long> refrigeratorIds = refrigeratorAccessService.findActiveRefrigeratorIds(userId);
        return new UserProfile(user.getId(), user.getNickname(), email, user.getProfileImageKey(),
                refrigeratorIds, user.getCookingCount());
    }

    /**
     * 사진 업로드를 검증한 뒤 본인의 닉네임과 사진을 하나의 트랜잭션으로 변경합니다.
     * @param userId 인증된 회원 ID
     * @param nickname 정규화와 형식 검증을 마친 닉네임
     * @param image 사진 유지·초기화·교체 요청
     * @return 프로필 변경을 커밋한 회원
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public User updateProfile(Long userId, String nickname, ProfileImageChange image) {
        nicknamePolicy.validate(nickname);
        if (image instanceof ProfileImageChange.Replace replacement) {
            profileImages.validateUpload(userId, replacement.key());
        }
        try {
            return transaction.execute(status -> updateLockedProfile(userId, nickname, image));
        } catch (DataIntegrityViolationException exception) {
            throw UserConstraintExceptionTranslator.translate(exception);
        }
    }

    private User updateLockedProfile(Long userId, String nickname, ProfileImageChange image) {
        User user = userAccessService.lockActive(userId);
        if (!user.getNickname().equals(nickname) && users.existsByNickname(nickname)) {
            throw new CustomException(UserExceptionCode.NICKNAME_DUPLICATE);
        }
        user.updateProfile(nickname, image.resolve(user.getProfileImageKey(), defaultImageKey));
        users.flush();
        return user;
    }
}
