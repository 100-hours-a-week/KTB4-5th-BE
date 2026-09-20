package com.dameokja.backend.user.application;

import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.domain.UserStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserWithdrawalServiceTest extends UserWithdrawalUnitTest {
    @Test
    void anonymizesUserAndRequestsOwnedMembershipDeletion() {
        User user = existingUser();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        userWithdrawalService.withdraw(1L);
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getNickname()).isNotEqualTo("User1").hasSizeBetween(2, 10);
        assertThat(user.getLoginId()).isNull();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getPasswordChangedAt()).isNull();
        assertThat(user.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 9, 17, 12, 0));
        verify(refrigeratorLifecycleService).deleteOwned(1L, user.getDeletedAt());
    }

    @Test
    void retriesReplacementNicknameWhenItAlreadyExists() {
        User user = existingUser();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname(anyString())).thenReturn(true, false);
        userWithdrawalService.withdraw(1L);
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        verify(userRepository, times(2)).existsByNickname(anyString());
    }

    @Test
    void repeatedWithdrawalPreservesOriginalState() {
        User user = existingUser();
        LocalDateTime deletedAt = LocalDateTime.of(2026, 9, 16, 12, 0);
        user.withdraw("withdrawn1", deletedAt);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        userWithdrawalService.withdraw(1L);
        assertThat(user.getDeletedAt()).isEqualTo(deletedAt);
        assertThat(user.getNickname()).isEqualTo("withdrawn1");
        verifyNoInteractions(refrigeratorLifecycleService);
    }

    @Test
    void rejectsMissingUser() {
        assertUserError(UserExceptionCode.USER_NOT_FOUND, () -> userWithdrawalService.withdraw(1L));
        verifyNoInteractions(refrigeratorLifecycleService);
    }
}
