package com.dameokja.backend.user.application;

import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.UserRepository;
import com.dameokja.backend.user.domain.UserStatus;
import com.dameokja.backend.auth.application.AuthService;
import org.springframework.stereotype.Service;
import com.dameokja.backend.refrigerator.application.RefrigeratorLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class UserWithdrawalService {
    private static final String WITHDRAWN_NICKNAME_PREFIX = "w";
    private static final int RANDOM_NICKNAME_LENGTH = 9;

    private final UserRepository userRepository;
    private final RefrigeratorLifecycleService refrigeratorLifecycleService;
    private final Clock clock;
    private final AuthService authService;

    public void withdraw(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(UserExceptionCode.USER_NOT_FOUND));
        if (user.getStatus() == UserStatus.WITHDRAWN) {
            authService.revokeAllUserSessions(userId);
            return;
        }
        LocalDateTime withdrawnAt = now();
        refrigeratorLifecycleService.deleteOwned(userId, withdrawnAt);
        user.withdraw(generateReplacementNickname(), withdrawnAt);
        userRepository.flush();
        authService.revokeAllUserSessions(userId);
    }

    private String generateReplacementNickname() {
        String replacementNickname;
        do {
            String randomNicknameSuffix = UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, RANDOM_NICKNAME_LENGTH);
            replacementNickname = WITHDRAWN_NICKNAME_PREFIX + randomNicknameSuffix;
        } while (userRepository.existsByNickname(replacementNickname));
        return replacementNickname;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
    }
}
