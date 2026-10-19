package com.dameokja.backend.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.user.domain.SocialAccount;
import com.dameokja.backend.user.infrastructure.SocialAccountRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.UserRepository;
import com.dameokja.backend.image.application.ProfileImageService;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {
    @Mock private UserRepository users;
    @Mock private SocialAccountRepository socialAccounts;
    @Mock private RefrigeratorAccessService refrigerators;
    private UserProfileService service;

    @BeforeEach
    void setUp() {
        service = new UserProfileService(new UserAccessService(users), refrigerators, socialAccounts, users,
                mock(NicknamePolicy.class), mock(ProfileImageService.class), mock(PlatformTransactionManager.class), "profiles/default.png");
    }

    @Test
    void returnsStoredProfileAndCurrentlySelectedRefrigerator() {
        User user = user();
        ReflectionTestUtils.setField(user, "cookingCount", 3);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(socialAccounts.findByUserIdAndProvider(1L, OAuthProvider.KAKAO))
                .thenReturn(Optional.of(new SocialAccount(user, OAuthProvider.KAKAO, "123", "dave@example.com")));
        when(refrigerators.findActiveRefrigeratorIds(1L)).thenReturn(List.of(9L));

        UserProfile profile = service.getProfile(1L);

        assertThat(profile.id()).isEqualTo(1L);
        assertThat(profile.nickname()).isEqualTo("dave");
        assertThat(profile.email()).isEqualTo("dave@example.com");
        assertThat(profile.profileImageKey()).isEqualTo("profiles/default.png");
        assertThat(profile.activeRefrigeratorIds()).containsExactly(9L);
        assertThat(profile.cookingCount()).isEqualTo(3);
    }

    @Test
    void returnsEmptyListWhenNoActiveRefrigeratorExists() {
        when(users.findById(1L)).thenReturn(Optional.of(user()));
        when(refrigerators.findActiveRefrigeratorIds(1L)).thenReturn(List.of());

        assertThat(service.getProfile(1L).activeRefrigeratorIds()).isEmpty();
    }

    @Test
    void rejectsWithdrawnUserBeforeReadingRefrigerators() {
        User user = user();
        user.withdraw("withdrawn", LocalDateTime.of(2026, 10, 16, 12, 0));
        when(users.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.getProfile(1L))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(UserExceptionCode.USER_NOT_ACTIVE));
        verifyNoInteractions(refrigerators, socialAccounts);
    }

    @Test
    void rejectsMissingUserBeforeReadingRefrigerators() {
        when(users.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile(1L))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(UserExceptionCode.USER_NOT_FOUND));
        verifyNoInteractions(refrigerators, socialAccounts);
    }

    @Test
    void returnsNullEmailWhenKakaoAccountIsMissing() {
        when(users.findById(1L)).thenReturn(Optional.of(user()));
        when(socialAccounts.findByUserIdAndProvider(1L, OAuthProvider.KAKAO)).thenReturn(Optional.empty());

        assertThat(service.getProfile(1L).email()).isNull();
    }

    @Test
    void returnsNullEmailWhenStoredKakaoEmailIsMissing() {
        User user = user();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(socialAccounts.findByUserIdAndProvider(1L, OAuthProvider.KAKAO))
                .thenReturn(Optional.of(new SocialAccount(user, OAuthProvider.KAKAO, "123", null)));

        assertThat(service.getProfile(1L).email()).isNull();
    }

    private User user() {
        User user = new User("dave", "profiles/default.png");
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }
}
